package dev.freya02.botcommands.restart.jda.cache

import dev.freya02.botcommands.restart.jda.cache.transformer.BContextImplTransformer
import dev.freya02.botcommands.restart.jda.cache.transformer.JDABuilderTransformer
import dev.freya02.botcommands.restart.jda.cache.transformer.JDAImplTransformer
import dev.freya02.botcommands.restart.jda.cache.transformer.JDAServiceTransformer
import java.lang.instrument.Instrumentation

object Agent {

    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        println("Agent args: $agentArgs")
        inst.addTransformer(JDABuilderTransformer)
        inst.addTransformer(JDAServiceTransformer)
        inst.addTransformer(BContextImplTransformer)
        inst.addTransformer(JDAImplTransformer)
    }
}