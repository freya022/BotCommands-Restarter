package dev.freya02.botcommands.restart.jda.cache

import io.github.freya022.botcommands.api.core.JDAService
import io.github.freya022.botcommands.api.core.events.BReadyEvent
import io.mockk.*
import net.dv8tion.jda.api.hooks.IEventManager
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import kotlin.test.Test

class JDAServiceTransformerTest {

    class Bot : JDAService() {

        override val intents: Set<GatewayIntent> = emptySet()
        override val cacheFlags: Set<CacheFlag> = emptySet()

        public override fun createJDA(event: BReadyEvent, eventManager: IEventManager) {
            println("createJDA")
        }
    }

    @Test
    fun `Event listener is instrumented`() {
        mockkObject(JDABuilderSession)
        every { JDABuilderSession.withBuilderSession(any()) } answers { callOriginal() } // Will call onReadyEvent

        val onReadyEvent = JDAService::class.java.getDeclaredMethod("onReadyEvent\$BotCommands", BReadyEvent::class.java, IEventManager::class.java)
        val bot = mockk<Bot> {
            every { createJDA(any(), any()) } just runs
            every { onReadyEvent.invoke(this@mockk, any<BReadyEvent>(), any<IEventManager>()) } answers { callOriginal() } // Will call createJDA
        }

        val readyEvent = mockk<BReadyEvent>()
        val eventManager = mockk<IEventManager>()

        onReadyEvent.invoke(bot, readyEvent, eventManager)

        verify(exactly = 1) { bot.createJDA(readyEvent, eventManager) }
    }
}