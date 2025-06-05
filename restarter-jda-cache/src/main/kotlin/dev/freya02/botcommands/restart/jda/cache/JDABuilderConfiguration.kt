package dev.freya02.botcommands.restart.jda.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.hooks.IEventManager
import net.dv8tion.jda.api.hooks.InterfacedEventManager

private val logger = KotlinLogging.logger { }

class JDABuilderConfiguration internal constructor() {

    private val warnedUnsupportedValues: MutableSet<String> = hashSetOf()

    var hasUnsupportedValues = false
        private set

    private val builderValues: MutableMap<ValueType, Any?> = hashMapOf()
    private var _eventManager: IEventManager? = null
    val eventManager: IEventManager get() = _eventManager ?: InterfacedEventManager()

    // So we can track the initial token and intents, the constructor will be instrumented and call this method
    // The user overriding the values using token/intent setters should not be an issue
    @DynamicCall
    fun onInit(token: String?, intents: Int) {
        builderValues[ValueType.TOKEN] = token
        builderValues[ValueType.INTENTS] = intents
    }

    @DynamicCall
    fun markUnsupportedValue(signature: String) {
        if (warnedUnsupportedValues.add(signature))
            logger.warn { "Unsupported JDABuilder method '$signature', JDA will not be cached between restarts" }
        hasUnsupportedValues = true
    }

    @DynamicCall
    fun setStatus(status: OnlineStatus) {
        builderValues[ValueType.STATUS] = status
    }

    @DynamicCall
    fun setEventManager(eventManager: IEventManager) {
        _eventManager = eventManager
    }

    @DynamicCall
    fun setEventPassthrough(enable: Boolean) {
        builderValues[ValueType.EVENT_PASSTHROUGH] = enable
    }

    internal infix fun isSameAs(other: JDABuilderConfiguration): Boolean {
        return builderValues == other.builderValues
    }

    private enum class ValueType {
        TOKEN,
        INTENTS,
        STATUS,
        EVENT_PASSTHROUGH,
    }
}