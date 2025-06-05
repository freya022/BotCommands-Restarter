package dev.freya02.botcommands.restart.jda.cache

import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.hooks.IEventManager
import net.dv8tion.jda.api.hooks.InterfacedEventManager

class JDABuilderConfiguration internal constructor() {

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
    fun markUnsupportedValue() {
        // TODO log which method is incompatible, pass the method name using codegen
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

    infix fun isSameAs(other: JDABuilderConfiguration): Boolean {
        // TODO: implement
        return super.equals(other)
    }

    private enum class ValueType {
        TOKEN,
        INTENTS,
        STATUS,
    }
}