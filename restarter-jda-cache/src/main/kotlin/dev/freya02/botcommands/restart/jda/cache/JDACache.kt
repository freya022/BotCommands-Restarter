package dev.freya02.botcommands.restart.jda.cache

import net.dv8tion.jda.api.JDA

internal object JDACache {

    private val cache: MutableMap<String, Data> = hashMapOf()

    internal operator fun contains(key: String): Boolean = key in cache

    internal operator fun get(key: String): Data? = cache[key]

    internal operator fun set(key: String, data: Data) {
        cache[key] = data
    }

    internal fun remove(key: String): Data? = cache.remove(key)

    internal class Data internal constructor(
        val configuration: JDABuilderConfiguration,
        val jda: JDA,
        val doShutdown: Runnable,
    )
}