package dev.freya02.botcommands.restart.jda.cache

import dev.freya02.botcommands.restart.jda.cache.transformer.classDesc
import dev.freya02.botcommands.restart.jda.cache.transformer.createLambda
import dev.freya02.botcommands.restart.jda.cache.transformer.lambdaMetafactoryDesc
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.lang.constant.*
import java.lang.constant.ConstantDescs.CD_Object
import java.lang.constant.ConstantDescs.CD_void
import java.util.function.Supplier
import kotlin.test.assertEquals

private val CD_JDA = ClassDesc.of("net.dv8tion.jda.api.JDA")
private val CD_JDAImpl = ClassDesc.of("net.dv8tion.jda.internal.JDAImpl")
private val CD_JDABuilder = ClassDesc.of("net.dv8tion.jda.api.JDABuilder")
private val CD_IEventManager = ClassDesc.of("net.dv8tion.jda.api.hooks.IEventManager")

private val CD_JDAService = ClassDesc.of("io.github.freya022.botcommands.api.core.JDAService")
private val CD_BReadyEvent  = ClassDesc.of("io.github.freya022.botcommands.api.core.events.BReadyEvent")
private val CD_BContextImpl = ClassDesc.of("io.github.freya022.botcommands.internal.core.BContextImpl")

private val CD_Function0 = ClassDesc.of("kotlin.jvm.functions.Function0")

object CodeBuilderTest {

    @MethodSource("Test createLambda")
    @ParameterizedTest
    fun `Test createLambda`(expected: DynamicCallSiteDesc, actual: DynamicCallSiteDesc) {
        assertEquals(expected, actual)
    }

    @JvmStatic
    fun `Test createLambda`(): List<Arguments> = listOf(
        Arguments.of(
            DynamicCallSiteDesc.of(
                lambdaMetafactoryDesc,
                "get",
                MethodTypeDesc.of(classDesc<Supplier<*>>(), CD_JDABuilder),
                MethodTypeDesc.of(CD_Object),
                MethodHandleDesc.ofMethod(
                    DirectMethodHandleDesc.Kind.VIRTUAL,
                    CD_JDABuilder,
                    "doBuild",
                    MethodTypeDesc.of(CD_JDA)
                ),
                MethodTypeDesc.of(CD_Object),
            ),
            createLambda(
                interfaceMethod = Supplier<*>::get,
                targetType = CD_JDABuilder,
                targetMethod = "doBuild",
                targetMethodReturnType = CD_JDA,
                targetMethodArguments = listOf(),
                capturedTypes = listOf(),
                isStatic = false
            )
        ),

        Arguments.of(
            DynamicCallSiteDesc.of(
                lambdaMetafactoryDesc,
                "run",
                MethodTypeDesc.of(classDesc<Runnable>(), CD_JDAService, CD_BReadyEvent, CD_IEventManager),
                MethodTypeDesc.of(CD_void),
                MethodHandleDesc.ofMethod(
                    DirectMethodHandleDesc.Kind.VIRTUAL,
                    CD_JDAService,
                    $$"lambda$onReadyEvent$BotCommands$withBuilderSession",
                    MethodTypeDesc.of(CD_void, CD_BReadyEvent, CD_IEventManager)
                ),
                MethodTypeDesc.of(CD_void),
            ),
            createLambda(
                interfaceMethod = Runnable::run,
                targetType = CD_JDAService,
                targetMethod = $$"lambda$onReadyEvent$BotCommands$withBuilderSession",
                targetMethodReturnType = CD_void,
                targetMethodArguments = listOf(),
                capturedTypes = listOf(CD_BReadyEvent, CD_IEventManager),
                isStatic = false
            )
        ),

        Arguments.of(
            DynamicCallSiteDesc.of(
                lambdaMetafactoryDesc,
                "run",
                MethodTypeDesc.of(classDesc<Runnable>(), CD_JDAImpl),
                MethodTypeDesc.of(CD_void),
                MethodHandleDesc.ofMethod(
                    DirectMethodHandleDesc.Kind.VIRTUAL,
                    CD_JDAImpl,
                    "doShutdown",
                    MethodTypeDesc.of(CD_void)
                ),
                MethodTypeDesc.of(CD_void),
            ),
            createLambda(
                interfaceMethod = Runnable::run,
                targetType = CD_JDAImpl,
                targetMethod = "doShutdown",
                targetMethodReturnType = CD_void,
                targetMethodArguments = listOf(),
                capturedTypes = listOf(),
                isStatic = false
            )
        ),

        Arguments.of(
            DynamicCallSiteDesc.of(
                lambdaMetafactoryDesc,
                "run",
                MethodTypeDesc.of(classDesc<Runnable>(), CD_BContextImpl, CD_Function0),
                MethodTypeDesc.of(CD_void),
                MethodHandleDesc.ofMethod(
                    DirectMethodHandleDesc.Kind.VIRTUAL,
                    CD_BContextImpl,
                    "doScheduleShutdownSignal",
                    MethodTypeDesc.of(CD_void, CD_Function0)
                ),
                MethodTypeDesc.of(CD_void),
            ),
            createLambda(
                interfaceMethod = Runnable::run,
                targetType = CD_BContextImpl,
                targetMethod = "doScheduleShutdownSignal",
                targetMethodReturnType = CD_void,
                targetMethodArguments = listOf(),
                capturedTypes = listOf(CD_Function0),
                isStatic = false
            )
        ),
    )
}