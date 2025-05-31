package dev.freya02.botcommands.internal.restart.utils

import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory

object AppClasspath {

    fun getPaths(): List<Path> {
        return ManagementFactory.getRuntimeMXBean().classPath
            .split(File.pathSeparator)
            .map(::Path)
            .filter { it.isDirectory() }
    }
}