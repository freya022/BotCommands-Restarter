package dev.freya02.botcommands.internal.restart

import dev.freya02.botcommands.internal.restart.sources.DeletedSourceFile
import dev.freya02.botcommands.internal.restart.sources.SourceDirectories
import dev.freya02.botcommands.internal.restart.sources.SourceFile
import java.io.InputStream
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.*

internal class RestartClassLoader internal constructor(
    urls: List<URL>,
    parent: ClassLoader,
    private val sourceDirectories: SourceDirectories,
) : URLClassLoader(urls.toTypedArray(), parent) {

    override fun getResources(name: String): Enumeration<URL> {
        val resources = parent.getResources(name)
        val updatedFile = sourceDirectories.getFile(name)

        if (updatedFile != null) {
            if (resources.hasMoreElements()) {
                resources.nextElement()
            }
            if (updatedFile is SourceFile) {
                return MergedEnumeration(createFileUrl(name, updatedFile), resources)
            }
        }

        return resources
    }

    override fun getResource(name: String): URL? {
        val updatedFile = sourceDirectories.getFile(name)
        if (updatedFile is DeletedSourceFile) {
            return null
        }

        return findResource(name) ?: super.getResource(name)
    }

    override fun findResource(name: String): URL? {
        val updatedFile = sourceDirectories.getFile(name)
            ?: return super.findResource(name)
        return (updatedFile as? SourceFile)?.let { createFileUrl(name, it) }
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        val path = "${name.replace('.', '/')}.class"
        val updatedFile = sourceDirectories.getFile(path)
        if (updatedFile is DeletedSourceFile)
            throw ClassNotFoundException(name)

        return synchronized(getClassLoadingLock(name)) {
            val loadedClass = findLoadedClass(name) ?: try {
                findClass(name)
            } catch (_: ClassNotFoundException) {
                Class.forName(name, false, parent)
            }
            if (resolve) resolveClass(loadedClass)
            loadedClass
        }
    }

    override fun findClass(name: String): Class<*> {
        val path = "${name.replace('.', '/')}.class"
        val updatedFile = sourceDirectories.getFile(path)
            ?: return super.findClass(name)
        if (updatedFile is DeletedSourceFile)
            throw ClassNotFoundException(name)

        updatedFile as SourceFile
        return defineClass(name, updatedFile.bytes, 0, updatedFile.bytes.size)
    }

    @Suppress("DEPRECATION") // We target Java 17 but JDK 20 deprecates the URL constructors
    private fun createFileUrl(name: String, file: SourceFile): URL {
        return URL("reloaded", null, -1, "/$name", ClasspathFileURLStreamHandler(file))
    }

    private class ClasspathFileURLStreamHandler(
        private val file: SourceFile,
    ) : URLStreamHandler() {

        override fun openConnection(u: URL): URLConnection = Connection(u)

        private inner class Connection(url: URL): URLConnection(url) {

            override fun connect() {}

            override fun getInputStream(): InputStream = file.bytes.inputStream()

            override fun getLastModified(): Long = file.lastModified.toEpochMilli()

            override fun getContentLengthLong(): Long = file.bytes.size.toLong()
        }
    }

    private class MergedEnumeration<E>(private val first: E, private val rest: Enumeration<E>) : Enumeration<E> {

        private var hasConsumedFirst = false

        override fun hasMoreElements(): Boolean = !hasConsumedFirst || rest.hasMoreElements()

        override fun nextElement(): E? {
            if (!hasConsumedFirst) {
                hasConsumedFirst = true
                return first
            } else {
                return rest.nextElement()
            }
        }
    }
}