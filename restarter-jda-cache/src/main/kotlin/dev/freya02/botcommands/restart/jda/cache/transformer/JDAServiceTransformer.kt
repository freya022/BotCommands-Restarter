package dev.freya02.botcommands.restart.jda.cache.transformer

import dev.freya02.botcommands.restart.jda.cache.JDABuilderSession
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassFile.*
import java.lang.classfile.CodeModel
import java.lang.classfile.MethodModel
import java.lang.classfile.TypeKind
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_String
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.MethodTypeDesc

private val logger = KotlinLogging.logger { }

// Avoid importing BC and JDA classes
private val CD_BContext = ClassDesc.of("io.github.freya022.botcommands.api.core.BContext")
private val CD_JDAService = ClassDesc.of("io.github.freya022.botcommands.api.core.JDAService")
private val CD_BReadyEvent  = ClassDesc.of("io.github.freya022.botcommands.api.core.events.BReadyEvent")
private val CD_IEventManager = ClassDesc.of("net.dv8tion.jda.api.hooks.IEventManager")

internal object JDAServiceTransformer : AbstractClassFileTransformer("io/github/freya022/botcommands/api/core/JDAService") {

    private const val TARGET_METHOD_NAME = $$"onReadyEvent$BotCommands"
    private const val TARGET_METHOD_SIGNATURE = "(Lio/github/freya022/botcommands/api/core/events/BReadyEvent;Lnet/dv8tion/jda/api/hooks/IEventManager;)V"

    override fun transform(classData: ByteArray): ByteArray {
        val classFile = ClassFile.of()
        var hasModifiedMethod = false
        val newBytes = classFile.transformClass(classFile.parse(classData)) { classBuilder, classElement ->
            val methodModel = classElement as? MethodModel ?: return@transformClass classBuilder.retain(classElement)
            if (!methodModel.methodName().equalsString(TARGET_METHOD_NAME)) return@transformClass classBuilder.retain(classElement)
            if (!methodModel.methodType().equalsString(TARGET_METHOD_SIGNATURE)) return@transformClass classBuilder.retain(classElement)

            hasModifiedMethod = true

            // Put the original code of onReadyEvent in the lambda,
            // it will be fired by JDABuilderSession.withBuilderSession in onReadyEvent
            val lambdaName = $$"lambda$onReadyEvent$BotCommands$withBuilderSession"
            classBuilder.withMethodBody(
                lambdaName,
                MethodTypeDesc.ofDescriptor(TARGET_METHOD_SIGNATURE),
                ACC_PRIVATE or ACC_SYNTHETIC or ACC_FINAL
            ) { codeBuilder ->

                val codeModel = methodModel.code().get()
                codeModel.forEach { codeBuilder.with(it) }
            }

            classBuilder.transformMethod(methodModel) { methodBuilder, methodElement ->
                if (methodElement !is CodeModel) return@transformMethod methodBuilder.retain(methodElement)

                methodBuilder.withCode { codeBuilder ->
                    val thisSlot = codeBuilder.receiverSlot()

                    val readyEventSlot = codeBuilder.parameterSlot(0)
                    val eventManagerSlot = codeBuilder.parameterSlot(1)

                    val contextSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)
                    val sessionKeySlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)
                    val sessionRunnableSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)

                    // var context = event.getContext()
                    // We could inline this to avoid a successive store/load,
                    // but I think using variables is probably a better practice, let's leave the optimization to the VM
                    codeBuilder.aload(readyEventSlot)
                    codeBuilder.invokevirtual(CD_BReadyEvent, "getContext", MethodTypeDesc.of(CD_BContext))
                    codeBuilder.astore(contextSlot)

                    // var key = JDABuilderSession.getCacheKey(context)
                    codeBuilder.aload(contextSlot)
                    codeBuilder.invokestatic(classDesc<JDABuilderSession>(), "getCacheKey", MethodTypeDesc.of(CD_String, CD_BContext))
                    codeBuilder.astore(sessionKeySlot)

                    // THE KEY IS NULLABLE
                    // If it is, then don't make a session
                    val nullKeyLabel = codeBuilder.newLabel()

                    // if (key == null) -> nullKeyLabel
                    codeBuilder.aload(sessionKeySlot)
                    codeBuilder.ifnull(nullKeyLabel)

                    // Runnable sessionRunnable = () -> [lambdaName](event, eventManager)
                    codeBuilder.aload(thisSlot)
                    codeBuilder.aload(readyEventSlot)
                    codeBuilder.aload(eventManagerSlot)
                    codeBuilder.invokedynamic(createLambda(
                        interfaceMethod = Runnable::run,
                        targetType = CD_JDAService,
                        targetMethod = lambdaName,
                        targetMethodReturnType = CD_void,
                        targetMethodArguments = listOf(),
                        capturedTypes = listOf(CD_BReadyEvent, CD_IEventManager),
                        isStatic = false
                    ))
                    codeBuilder.astore(sessionRunnableSlot)

                    // JDABuilderSession.withBuilderSession(key, this::[lambdaName])
                    codeBuilder.aload(sessionKeySlot)
                    codeBuilder.aload(sessionRunnableSlot)
                    codeBuilder.invokestatic(classDesc<JDABuilderSession>(), "withBuilderSession", MethodTypeDesc.of(CD_void, CD_String, classDesc<Runnable>()))

                    // Required
                    codeBuilder.return_()

                    // nullKeyLabel code
                    codeBuilder.labelBinding(nullKeyLabel)
                    codeBuilder.return_()
                }
            }
        }

        check(hasModifiedMethod) {
            "Could not find JDAService#onReadyEvent(BReadyEvent, IEventManager)"
        }

        return newBytes
    }
}