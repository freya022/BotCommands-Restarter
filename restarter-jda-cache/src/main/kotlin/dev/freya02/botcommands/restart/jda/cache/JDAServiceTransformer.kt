package dev.freya02.botcommands.restart.jda.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassFile.*
import java.lang.classfile.CodeModel
import java.lang.classfile.MethodModel
import java.lang.constant.*
import java.lang.constant.ConstantDescs.CD_String
import java.lang.constant.ConstantDescs.CD_void
import java.lang.invoke.*

private val logger = KotlinLogging.logger { }

internal object JDAServiceTransformer : AbstractClassFileTransformer("io/github/freya022/botcommands/api/core/JDAService") {

    private const val TARGET_METHOD_NAME = "onReadyEvent\$BotCommands"
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
            val lambdaName = "lambda\$onReadyEvent\$BotCommands\$withBuilderSession"
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

                    codeBuilder.aload(thisSlot)
                    codeBuilder.aload(readyEventSlot)
                    codeBuilder.aload(eventManagerSlot)
                    codeBuilder.invokedynamic(DynamicCallSiteDesc.of(
                        MethodHandleDesc.ofMethod(
                            DirectMethodHandleDesc.Kind.STATIC,
                            classDesc<LambdaMetafactory>(),
                            "metafactory",
                            MethodTypeDesc.of(classDesc<CallSite>(), classDesc<MethodHandles.Lookup>(), CD_String, classDesc<MethodType>(), classDesc<MethodType>(), classDesc<MethodHandle>(), classDesc<MethodType>())
                        ),
                        // The following parameters are from [[LambdaMetafactory#metafactory]]
                        // This is the 2nd argument of LambdaMetafactory#metafactory, "interfaceMethodName",
                        // the method name in Runnable is "run"
                        "run",
                        // This is the 3rd argument of LambdaMetafactory#metafactory, "factoryType",
                        // the return type is the implemented interface,
                        // while the parameters are the captured variables
                        MethodTypeDesc.of(classDesc<Runnable>(), JDAServiceClassDesc, BReadyEventClassDesc, IEventManagerClassDesc),
                        // Bootstrap arguments (see `javap -c -v <class file>` from a working .java sample)
                        // This is the 4th argument of LambdaMetafactory#metafactory, "interfaceMethodType",
                        // which is the signature of the implemented method, in this case, void Runnable.run()
                        MethodTypeDesc.of(CD_void),
                        // This is the 5th argument of LambdaMetafactory#metafactory, "implementation",
                        // this is the method to be called when invoking the lambda,
                        // with the captured variables and parameters
                        MethodHandleDesc.ofMethod(
                            DirectMethodHandleDesc.Kind.VIRTUAL,
                            JDAServiceClassDesc,
                            lambdaName,
                            MethodTypeDesc.of(CD_void, BReadyEventClassDesc, IEventManagerClassDesc)
                        ),
                        // This is the 6th argument of LambdaMetafactory#metafactory, "dynamicMethodType",
                        // this is "the signature and return type to be enforced dynamically at invocation type"
                        // This is usually the same as "interfaceMethodType"
                        MethodTypeDesc.of(CD_void),
                    ))

                    codeBuilder.invokestatic(classDesc<JDABuilderSession>(), "withBuilderSession", MethodTypeDesc.of(CD_void, classDesc<Runnable>()))
                    codeBuilder.return_()
                }
            }
        }

        check(hasModifiedMethod) {
            "Could not find JDAService#onReadyEvent(BReadyEvent, IEventManager)"
        }

        return newBytes
    }

    private val JDAServiceClassDesc: ClassDesc
        get() = ClassDesc.ofDescriptor("Lio/github/freya022/botcommands/api/core/JDAService;")

    private val BReadyEventClassDesc: ClassDesc
        get() = ClassDesc.ofDescriptor("Lio/github/freya022/botcommands/api/core/events/BReadyEvent;")

    private val IEventManagerClassDesc: ClassDesc
        get() = ClassDesc.ofDescriptor("Lnet/dv8tion/jda/api/hooks/IEventManager;")
}