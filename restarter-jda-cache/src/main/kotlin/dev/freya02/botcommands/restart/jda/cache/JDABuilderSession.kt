package dev.freya02.botcommands.restart.jda.cache

import io.github.freya022.botcommands.api.core.BContext
import io.github.oshai.kotlinlogging.KotlinLogging
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.OnlineStatus
import java.util.function.Supplier

private val logger = KotlinLogging.logger { }

class JDABuilderSession(
    private val key: String,
) {

    private var isIncompatible = false
    var wasBuilt: Boolean = false
        private set
    private val builderValues: MutableMap<ValueType, Any?> = hashMapOf()

    // So we can track the initial token and intents, the constructor will be instrumented and call this method
    // The user overriding the values using token/intent setters should not be an issue
    fun onInit(token: String?, intents: Int) {
        builderValues[ValueType.TOKEN] = token
        builderValues[ValueType.INTENTS] = intents
    }

    fun markIncompatible() {
        isIncompatible = true
    }

    fun setStatus(status: OnlineStatus) {
        builderValues[ValueType.STATUS] = status
    }

    fun onBuild(buildFunction: Supplier<JDA>): JDA {
        val jda: JDA
        if (isIncompatible) {
            logger.debug { "Configured JDABuilder is incompatible, building a new JDA instance with key '$key'" }
            jda = buildFunction.get()
            JDACache[key] = jda
        } else if (key in JDACache) {
            logger.debug { "Reusing JDA instance with key '$key'" }
            jda = JDACache[key]!!
            // TODO need to set the event manager to the new instance
            //  Pass the new IEventManager to session constructor
            //  then wrap and set it here
        } else {
            logger.debug { "Saving a new JDA instance with key '$key'" }
            jda = buildFunction.get()
            JDACache[key] = jda
            // TODO wrap event manager and set it
        }

        wasBuilt = true

        return jda
    }

    enum class ValueType {
        TOKEN,
        INTENTS,
        STATUS,
    }

    companion object {
        private val _currentSession: ThreadLocal<JDABuilderSession> =
            ThreadLocal.withInitial { error("No JDABuilderSession exists for this thread") }

        @JvmStatic
        fun currentSession(): JDABuilderSession {
            return _currentSession.get()
        }

        @JvmStatic
        fun getCacheKey(context: BContext): String? {
            return context.config.restartConfig.cacheKey
        }

        @JvmStatic
        fun withBuilderSession(
            key: String,
            // Use Java function types to make codegen a bit more reliable
            block: Runnable
        ) {
            val session = JDABuilderSession(key)
            _currentSession.set(session)
            try {
                block.run()
                if (!session.wasBuilt) {
                    logger.warn { "Could not save/restore any JDA session as none were built" }
                }
            } finally {
                _currentSession.remove()
            }
        }
    }
}