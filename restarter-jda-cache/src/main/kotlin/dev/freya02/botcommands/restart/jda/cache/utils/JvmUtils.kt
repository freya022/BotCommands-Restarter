package dev.freya02.botcommands.restart.jda.cache.utils

internal fun isJvmShuttingDown() = try {
    Runtime.getRuntime().removeShutdownHook(NullShutdownHook)
    false
} catch (_: IllegalStateException) {
    true
}

private object NullShutdownHook : Thread()