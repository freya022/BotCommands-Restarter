package dev.freya02.botcommands.restart.jda.cache

import io.github.freya022.botcommands.api.core.BContext
import io.github.oshai.kotlinlogging.KotlinLogging
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.OnlineStatus
import java.util.function.Supplier

private val logger = KotlinLogging.logger { }

// TODO there may be an issue with REST requests,
//  as the instance will not get shut down, the requester will still run any request currently queued
//  so we should find a way to cancel the tasks in the rate limiter

// TODO a similar feature exists at https://github.com/LorittaBot/DeviousJDA/blob/master/src/examples/java/SessionCheckpointAndGatewayResumeExample.kt
//  however as it is a JDA fork, users will not be able to use the latest features,
//  there is also a risk that the saved data (checkpoint) could miss fields

// TODO another way of building this feature is to have the user use an external gateway proxy, such as https://github.com/Gelbpunkt/gateway-proxy
//  however such a solution introduces a lot of friction,
//  requiring to set up JDA manually, though not complicated, but also docker and that container's config
//  An hybrid way would require rewriting that proxy,
//  so our module can hook into JDA and set the gateway URL to the proxy's
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
        // TODO log which method is incompatible, pass the method name using codegen
        isIncompatible = true
    }

    fun setStatus(status: OnlineStatus) {
        builderValues[ValueType.STATUS] = status
    }

    // TODO make onShutdown(shutdownFunction: Runnable)
    //  Do nothing initially, save the callback
    //  When building the new instance, shutdown if the new instance is incompatible
    //  If it is compatible then swap the event manager and send the events
    //  This may actually not be an actual swap, we could use a SPI to provide our own IEventManager implementation,
    //  which we use on all instances, this way we can control exactly when to buffer events
    //  and when to release them to the actual event manager

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

        // TODO maybe we should pass the IEventManager so we can set it on the new/current event manager
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