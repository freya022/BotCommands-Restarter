package dev.freya02.botcommands.internal.restart.services

import dev.freya02.botcommands.internal.restart.Restarter
import io.github.classgraph.ClassGraph
import io.github.freya022.botcommands.internal.core.ClassGraphConfigurer

internal class RestarterClassGraphConfigurer : ClassGraphConfigurer {

    override fun ClassGraph.configure(arguments: ClassGraphConfigurer.Arguments) {
        val thread = Thread.currentThread()
        val classLoader = thread.contextClassLoader
        if (thread.name != Restarter.RESTARTED_THREAD_NAME) return

        // [[Restarter]] will read from the mutable classes first then delegate to the app class loader (immutable)
        overrideClassLoaders(classLoader)
    }
}