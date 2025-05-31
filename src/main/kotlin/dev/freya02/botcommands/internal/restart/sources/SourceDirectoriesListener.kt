package dev.freya02.botcommands.internal.restart.sources

fun interface SourceDirectoriesListener {
    fun onChange(command: () -> Unit)
}