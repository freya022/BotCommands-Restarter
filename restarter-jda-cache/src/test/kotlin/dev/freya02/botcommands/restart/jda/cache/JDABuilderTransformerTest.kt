package dev.freya02.botcommands.restart.jda.cache

import io.mockk.*
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.requests.GatewayIntent
import okhttp3.OkHttpClient
import kotlin.test.Test

class JDABuilderTransformerTest {

    @Test
    fun `Constructor is instrumented`() {
        val builderConfiguration = mockk<JDABuilderConfiguration> {
            every { onInit(any(), any()) } just runs
            every { markUnsupportedValue() } just runs
        }

        mockkObject(JDABuilderSession)
        every { JDABuilderSession.currentSession().configuration } answers { builderConfiguration }

        JDABuilder.create("MY_TOKEN", setOf(GatewayIntent.GUILD_MEMBERS))

        verify(exactly = 1) { builderConfiguration.onInit("MY_TOKEN", GatewayIntent.getRaw(GatewayIntent.GUILD_MEMBERS)) }
    }

    @Test
    fun `Unsupported instance method invalidates cache`() {
        // Initial set up, this *may* call "markIncompatible" so we need to do it before really mocking
        val builder = createJDABuilder()

        // Actual test
        val builderConfiguration = mockk<JDABuilderConfiguration> {
            every { onInit(any(), any()) } just runs
            every { markUnsupportedValue() } just runs
        }

        mockkObject(JDABuilderSession)
        every { JDABuilderSession.currentSession().configuration } returns builderConfiguration

        builder.setHttpClientBuilder(OkHttpClient.Builder())

        verify(exactly = 1) { builderConfiguration.markUnsupportedValue() }
    }

    @Test
    fun `Instance method is instrumented`() {
        // Initial set up, this *may* call "markIncompatible" so we need to do it before really mocking
        val builder = createJDABuilder()

        // Actual test
        val builderConfiguration = mockk<JDABuilderConfiguration> {
            every { onInit(any(), any()) } just runs
            every { setStatus(any()) } just runs
        }

        mockkObject(JDABuilderSession)
        every { JDABuilderSession.currentSession().configuration } returns builderConfiguration

        builder.setStatus(OnlineStatus.DO_NOT_DISTURB)

        verify(exactly = 1) { builderConfiguration.setStatus(OnlineStatus.DO_NOT_DISTURB) }
    }

    @Test
    fun `Build method is instrumented`() {
        val builderConfiguration = mockk<JDABuilderConfiguration> {
            every { onInit(any(), any()) } just runs
            every { markUnsupportedValue() } just runs
        }

        val builderSession = mockk<JDABuilderSession> {
            every { onBuild(any()) } returns mockk()
            every { configuration } returns builderConfiguration
        }

        mockkObject(JDABuilderSession)
        every { JDABuilderSession.currentSession() } returns builderSession

        JDABuilder.createDefault("MY_TOKEN").build()

        verify(exactly = 1) { builderSession.onBuild(any()) }
    }

    /**
     * Creates a basic JDABuilder,
     * call this on the first line to not record any mocking data before doing the actual test.
     */
    private fun createJDABuilder(): JDABuilder {
        lateinit var builder: JDABuilder
        mockkObject(JDABuilderSession) {
            every { JDABuilderSession.currentSession().configuration } returns mockk(relaxUnitFun = true)

            builder = JDABuilder.create("MY_TOKEN", emptySet())
        }

        return builder
    }
}