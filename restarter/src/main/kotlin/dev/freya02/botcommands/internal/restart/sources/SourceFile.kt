package dev.freya02.botcommands.internal.restart.sources

import java.time.Instant

internal sealed interface ISourceFile

internal class SourceFile(
    val lastModified: Instant,
) : ISourceFile {

    val bytes: ByteArray
        get() = throw UnsupportedOperationException("Class data is no longer retained as RestartClassLoader is not used yet")
}

internal object DeletedSourceFile : ISourceFile