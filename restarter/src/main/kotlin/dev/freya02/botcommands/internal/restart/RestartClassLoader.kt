package dev.freya02.botcommands.internal.restart

import java.net.URL
import java.net.URLClassLoader

internal class RestartClassLoader internal constructor(
    urls: List<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls.toTypedArray(), parent)