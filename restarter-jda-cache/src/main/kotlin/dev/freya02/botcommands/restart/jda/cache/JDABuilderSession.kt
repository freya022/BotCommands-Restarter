package dev.freya02.botcommands.restart.jda.cache

import io.github.freya022.botcommands.api.core.BContext
import io.github.oshai.kotlinlogging.KotlinLogging
import net.dv8tion.jda.api.JDA
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

    @get:DynamicCall
    val configuration = JDABuilderConfiguration()
    var wasBuilt: Boolean = false
        private set

    @DynamicCall
    fun onShutdown(instance: JDA, shutdownFunction: Runnable) {
        JDACache[key] = JDACache.Data(configuration, instance, shutdownFunction)
    }

    @DynamicCall
    fun onBuild(buildFunction: Supplier<JDA>): JDA {
        val jda = buildOrReuse(buildFunction)
        wasBuilt = true
        return jda
    }

    private fun buildOrReuse(buildFunction: Supplier<JDA>): JDA {
        if (configuration.hasUnsupportedValues) {
            logger.debug { "Configured JDABuilder has unsupported values, building a new JDA instance (key '$key')" }
            val jda = buildFunction.get()
            val oldInstanceData = JDACache.remove(key)
            oldInstanceData?.doShutdown?.run()
            // TODO: Get event manager then wrap it into our special one
            return jda
        }

        fun createNewInstance(): JDA {
            val jda = buildFunction.get()
            // TODO: Get event manager then wrap it into our special one
            return jda
        }

        val cachedData = JDACache[key]
        if (cachedData == null) {
            logger.debug { "Creating a new JDA instance (key '$key')" }
            return createNewInstance()
        }

        if (cachedData.configuration isSameAs configuration) {
            logger.debug { "Reusing JDA instance with compatible configuration (key '$key')" }
            val jda = JDACache[key]!!.jda
            // TODO: Get event manager, cast it to our special one,
            //  set the delegate to value of JDA#getEventManager,
            //  then flush the event cache to it
            // TODO: Also send start up events again
            return jda
        } else {
            logger.debug { "Creating a new JDA instance as its configuration changed (key '$key')" }
            return createNewInstance()
        }
    }

    companion object {
        // I would store them in a Map, but JDABuilder has no idea what the key is
        private val activeSession: ThreadLocal<JDABuilderSession> =
            ThreadLocal.withInitial { error("No JDABuilderSession exists for this thread") }

        @JvmStatic
        @DynamicCall
        fun currentSession(): JDABuilderSession = activeSession.get()

        @JvmStatic
        @DynamicCall
        fun getCacheKey(context: BContext): String? = context.config.restartConfig.cacheKey

        @JvmStatic
        @DynamicCall
        fun withBuilderSession(
            key: String,
            // Use Java function types to make codegen a bit more reliable
            block: Runnable
        ) {
            val session = JDABuilderSession(key)
            activeSession.set(session)
            try {
                block.run()
                if (!session.wasBuilt) {
                    logger.warn { "Could not save/restore any JDA session as none were built" }
                }
            } finally {
                activeSession.remove()
            }
        }
    }
}