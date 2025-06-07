package dev.freya02.botcommands.internal.restart.sources

internal fun interface SourceDirectoryListener {
    fun onChange(sourcesFilesFactory: () -> SourceFiles)
}