package dev.freya02.botcommands.internal.restart

import dev.freya02.botcommands.internal.restart.sources.SourceDirectories
import dev.freya02.botcommands.internal.restart.utils.AppClasspath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URL
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger { }

class Restarter private constructor(
    private val args: Array<String>,
    private val sourceDirectories: SourceDirectories,
) {

    private val appClassLoader: ClassLoader
    val appClasspathUrls: List<URL>

    private val mainClassName: String

    private val uncaughtExceptionHandler: Thread.UncaughtExceptionHandler

    private val stopLock: Lock = ReentrantLock()
    private val listeners: MutableList<RestartListener> = arrayListOf()

    private val leakSafeExecutor = LeakSafeExecutor()

    init {
        val thread = Thread.currentThread()

        appClassLoader = thread.contextClassLoader
        appClasspathUrls = AppClasspath.getPaths().map { it.toUri().toURL() }

        mainClassName = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
            .walk { stream -> stream.filter { it.methodName == "main" }.toList().last() }
            .declaringClass.name

        uncaughtExceptionHandler = thread.uncaughtExceptionHandler
    }

    fun addListener(listener: RestartListener) {
        listeners += listener
    }

    private fun initialize(): Nothing {
        val throwable = leakSafeExecutor.callAndWait { start() }
        if (throwable != null)
            throw throwable
        ImmediateRestartException.throwAndHandle()
    }

    /**
     * Runs each [RestartListener.beforeStop] and then starts a new instance of the main class,
     * if the new instance fails, the [Throwable] is returned.
     */
    fun restart(): Throwable? {
        logger.debug { "Restarting application in '$mainClassName'" }
        // Do it from the original class loader, so the context is the same as for the initial restart
        return leakSafeExecutor.callAndWait {
            stop()
            start()
        }
    }

    private fun stop() {
        stopLock.withLock {
            listeners.forEach { it.beforeStop() }
            listeners.clear()
        }
        // All threads should be stopped at that point
        // so the GC should be able to remove all the previous loaded classes
        System.gc()
    }

    /**
     * Starts a new instance of the main class, or returns a [Throwable] if it failed.
     */
    private fun start(): Throwable? {
        val restartClassLoader = RestartClassLoader(appClasspathUrls, appClassLoader, sourceDirectories)
        var error: Throwable? = null
        val launchThreads = thread(name = "restartedMain", isDaemon = false, contextClassLoader = restartClassLoader) {
            try {
                val mainClass = Class.forName(mainClassName, false, restartClassLoader)
                val mainMethod = mainClass.getDeclaredMethod("main", Array<String>::class.java)
                mainMethod.isAccessible = true
                mainMethod.invoke(null, args)
            } catch (ex: Throwable) {
                error = ex
            }
        }
        launchThreads.join()

        return error
    }

    companion object {

        private val instanceLock: Lock = ReentrantLock()
        lateinit var instance: Restarter
            private set

        fun initialize(args: Array<String>, sourceDirectories: SourceDirectories) {
            var newInstance: Restarter? = null
            instanceLock.withLock {
                if (::instance.isInitialized.not()) {
                    newInstance = Restarter(args, sourceDirectories)
                    instance = newInstance
                }
            }
            newInstance?.initialize()
        }
    }
}
