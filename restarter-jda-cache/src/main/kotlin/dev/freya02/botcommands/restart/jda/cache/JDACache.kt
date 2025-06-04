package dev.freya02.botcommands.restart.jda.cache

import net.dv8tion.jda.api.JDA

internal object JDACache {

    private val cache: MutableMap<String, JDA> = hashMapOf()

    internal operator fun contains(key: String): Boolean = key in cache

    internal operator fun get(key: String): JDA? = cache[key]

    internal operator fun set(key: String, instance: JDA) {
        cache[key] = instance
    }
}