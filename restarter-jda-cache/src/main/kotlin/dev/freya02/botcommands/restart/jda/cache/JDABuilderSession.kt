package dev.freya02.botcommands.restart.jda.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import net.dv8tion.jda.api.OnlineStatus

private val logger = KotlinLogging.logger { }

class JDABuilderSession {

    private var isIncompatible = false
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
        fun withBuilderSession(
            // Use Java function types to make codegen a bit more reliable
            block: Runnable
        ) {
            val session = JDABuilderSession()
            _currentSession.set(session)
            try {
                block.run()
            } finally {
                _currentSession.remove()
            }
        }
    }
}