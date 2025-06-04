package dev.freya02.botcommands.restart.jda.cache

import io.mockk.*
import net.dv8tion.jda.internal.JDAImpl
import kotlin.test.Test

class JDAImplTransformerTest {

    @Test
    fun `Shutdown method is instrumented`() {
        val builderSession = mockk<JDABuilderSession> {
            every { onShutdown(any()) } just runs
        }

        mockkObject(JDABuilderSession)
        every { JDABuilderSession.currentSession() } returns builderSession

        val jda = mockk<JDAImpl> {
            every { shutdown() } answers { callOriginal() }
        }

        jda.shutdown()

        verify(exactly = 1) { builderSession.onShutdown(any()) }
    }
}