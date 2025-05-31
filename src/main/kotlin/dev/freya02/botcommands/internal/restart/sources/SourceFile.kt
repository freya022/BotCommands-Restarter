package dev.freya02.botcommands.internal.restart.sources

import java.time.Instant

internal sealed interface ISourceFile

internal class SourceFile(
    val lastModified: Instant,
    val bytes: ByteArray,
) : ISourceFile

internal object DeletedSourceFile : ISourceFile