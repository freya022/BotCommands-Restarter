package dev.freya02.botcommands.internal.restart.sources

internal interface SourceDirectoriesListener {
    fun onChange(command: () -> Unit)

    fun onCancel()
}