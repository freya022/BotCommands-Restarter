package dev.freya02.botcommands.internal.restart.watcher

import dev.freya02.botcommands.internal.restart.Restarter
import dev.freya02.botcommands.internal.restart.sources.DeletedSourceFile
import dev.freya02.botcommands.internal.restart.sources.SourceFile
import dev.freya02.botcommands.internal.restart.sources.SourceFiles
import dev.freya02.botcommands.internal.restart.sources.plus
import dev.freya02.botcommands.internal.restart.utils.AppClasspath
import dev.freya02.botcommands.internal.restart.utils.walkDirectories
import dev.freya02.botcommands.internal.restart.utils.walkFiles
import io.github.freya022.botcommands.api.core.utils.joinAsList
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo
import kotlin.time.Duration

private val logger = KotlinLogging.logger { }

// Lightweight, singleton version of [[SourceDirectories]] + [[ClasspathListener]]
internal class ClasspathWatcher private constructor(
    private var settings: Settings?, // null = no instance registered = no restart can be scheduled
) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private lateinit var restartFuture: ScheduledFuture<*>

    private val watchService = FileSystems.getDefault().newWatchService()
    private val registeredDirectories: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val snapshots: MutableMap<Path, SourceFiles> = hashMapOf()

    init {
        AppClasspath.getPaths().forEach { classRoot ->
            require(classRoot.isDirectory())

            logger.trace { "Creating snapshot of ${classRoot.absolutePathString()}" }
            snapshots[classRoot] = classRoot.takeSnapshot()

            logger.trace { "Listening to ${classRoot.absolutePathString()}" }
            registerDirectories(classRoot)
        }

        thread(name = "Classpath watcher", isDaemon = true) {
            while (true) {
                val key = try {
                    watchService.take() // Wait for a change
                } catch (_: InterruptedException) {
                    return@thread logger.trace { "Interrupted watching classpath" }
                }
                val pollEvents = key.pollEvents()
                if (pollEvents.isNotEmpty()) {
                    logger.trace {
                        val affectedList = pollEvents.joinAsList { "${it.kind()}: ${it.context()}" }
                        "Affected files:\n$affectedList"
                    }
                } else {
                    // Seems to be empty when a directory gets deleted
                    // The next watch key *should* be an ENTRY_DELETE of that directory
                    continue
                }
                if (!key.reset()) {
                    logger.warn { "${key.watchable()} is no longer valid" }
                    continue
                }

                scheduleRestart()
            }
        }
    }

    private fun scheduleRestart() {
        val settings = settings ?: return // Don't schedule a restart until an instance has registered
        if (::restartFuture.isInitialized) restartFuture.cancel(false)
        restartFuture = scheduler.schedule(::tryRestart, settings.restartDelay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    private fun tryRestart() {
        // Can't set to null after restarting,
        // as the restart function only returns after the main method ran
        val settings = settings ?: return
        try {
            logger.debug { "Attempting to restart" }
            this.settings = null // Wait until the next instance has given its settings
            compareSnapshots()
            snapshots.keys.forEach { registerDirectories(it) }
            Restarter.instance.restart()
        } catch (e: Exception) {
            logger.error(e) { "Restart failed, waiting for the next build" }
            this.settings = settings // Reuse the old settings to reschedule a new restart
        }
    }

    private fun compareSnapshots() {
        val hasChanges = snapshots.any { (directory, files) ->
            val snapshot = directory.takeSnapshot()

            // Exclude deleted files so they don't count as being deleted again
            val deletedPaths = files.withoutDeletes().keys - snapshot.keys
            if (deletedPaths.isNotEmpty()) {
                logger.info { "${deletedPaths.size} files were deleted in ${directory.absolutePathString()}: $deletedPaths" }
                snapshots[directory] = deletedPaths.associateWith { DeletedSourceFile } + snapshot
                // So we can re-register them in case they are recreated
                registeredDirectories.removeAll(deletedPaths.map { directory.resolve(it) })
                return@any true
            }

            // Exclude deleted files so they count as being added back
            val addedPaths = snapshot.keys - files.withoutDeletes().keys
            if (addedPaths.isNotEmpty()) {
                logger.info { "${addedPaths.size} files were added in ${directory.absolutePathString()}: $addedPaths" }
                snapshots[directory] =  files + snapshot
                return@any true
            }

            val modifiedFiles = snapshot.keys.filter { key ->
                val actual = snapshot[key] ?: error("Key from map is missing a value somehow")
                val expected = files[key] ?: error("Expected file is missing, should have been detected as deleted")

                // File was deleted (on the 2nd build for example) and got recreated (on the 3rd build for example)
                if (expected is DeletedSourceFile) error("Expected file was registered as deleted, should have been detected as added")
                expected as SourceFile

                actual as SourceFile // Assertion

                actual.lastModified != expected.lastModified
            }
            if (modifiedFiles.isNotEmpty()) {
                logger.info { "${modifiedFiles.size} files were modified in ${directory.absolutePathString()}: $modifiedFiles" }
                snapshots[directory] = files + snapshot
                return@any true
            }

            false
        }

        if (!hasChanges)
            error("Received a file system event but no changes were detected")
    }

    private fun registerDirectories(directory: Path) {
        directory.walkDirectories { path, attributes ->
            if (registeredDirectories.add(path))
                path.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        }
    }

    private class Settings(
        val restartDelay: Duration,
    )

    internal companion object {
        private val instanceLock = ReentrantLock()
        internal lateinit var instance: ClasspathWatcher
            private set

        internal fun initialize(restartDelay: Duration) {
            instanceLock.withLock {
                val settings = Settings(restartDelay)
                if (::instance.isInitialized.not()) {
                    instance = ClasspathWatcher(settings)
                } else {
                    instance.settings = settings
                }
            }
        }
    }
}

private fun Path.takeSnapshot(): SourceFiles = walkFiles().associate { (it, attrs) ->
    it.relativeTo(this).pathString to SourceFile(attrs.lastModifiedTime().toInstant())
}.let(::SourceFiles)