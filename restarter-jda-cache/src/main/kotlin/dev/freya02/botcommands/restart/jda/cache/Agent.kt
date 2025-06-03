package dev.freya02.botcommands.restart.jda.cache

import java.lang.instrument.Instrumentation

object Agent {

    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        println("Agent args: $agentArgs")
        inst.addTransformer(JDABuilderTransformer)
        inst.addTransformer(JDAServiceTransformer)
    }
}