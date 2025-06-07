package dev.freya02.botcommands.restart.jda.cache.transformer

import dev.freya02.botcommands.restart.jda.cache.JDABuilderSession
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.classfile.*
import java.lang.classfile.ClassFile.*
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_String
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.MethodTypeDesc
import java.lang.reflect.AccessFlag

private val logger = KotlinLogging.logger { }

// Avoid importing BC and JDA classes
private val CD_BContext = ClassDesc.of("io.github.freya022.botcommands.api.core.BContext")
private val CD_BContextImpl = ClassDesc.of("io.github.freya022.botcommands.internal.core.BContextImpl")
private val CD_Function0 = ClassDesc.of("kotlin.jvm.functions.Function0")

internal object BContextImplTransformer : AbstractClassFileTransformer("io/github/freya022/botcommands/internal/core/BContextImpl") {

    override fun transform(classData: ByteArray): ByteArray {
        val classFile = ClassFile.of()
        val classModel = classFile.parse(classData)

        return classFile.transformClass(classModel,
            ScheduleShutdownSignalTransform(classModel)
        )
    }
}

private class ScheduleShutdownSignalTransform(private val classModel: ClassModel) : ClassTransform {

    override fun atStart(classBuilder: ClassBuilder) {
        val targetMethodModel = classModel.methods().firstOrNull(::isTargetMethod)
            ?: error("Could not find BContextImpl#${TARGET_METHOD_NAME}${TARGET_METHOD_SIGNATURE}")

        logger.trace { "Transferring BContextImpl#${TARGET_METHOD_NAME}${TARGET_METHOD_SIGNATURE} into $LAMBDA_NAME" }

        classBuilder.withMethodBody(
            LAMBDA_NAME,
            MethodTypeDesc.ofDescriptor(TARGET_METHOD_SIGNATURE),
            ACC_PRIVATE or ACC_SYNTHETIC or ACC_FINAL
        ) { codeBuilder ->
            val codeModel = targetMethodModel.code().get()
            codeModel.forEach { codeBuilder.with(it) }
        }
    }

    override fun accept(classBuilder: ClassBuilder, classElement: ClassElement) {
        val methodModel = classElement as? MethodModel ?: return classBuilder.retain(classElement)
        if (!isTargetMethod(methodModel)) return classBuilder.retain(classElement)

        logger.trace { "Transforming BContextImpl#${TARGET_METHOD_NAME}${TARGET_METHOD_SIGNATURE}" }

        classBuilder.transformMethod(methodModel) { methodBuilder, methodElement ->
            if (methodElement !is CodeModel) return@transformMethod methodBuilder.retain(methodElement)

            methodBuilder.withFlags(*(methodModel.flags().flags() - AccessFlag.PRIVATE + AccessFlag.PUBLIC).toTypedArray())

            methodBuilder.withCode { codeBuilder ->
                val thisSlot = codeBuilder.receiverSlot()

                val afterShutdownSignalSlot = codeBuilder.parameterSlot(0)
                val doScheduleShutdownSignalSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)
                val sessionKeySlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)
                val builderSessionSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)

                // Runnable doScheduleShutdownSignal = () -> this.doScheduleShutdownSignal(afterShutdownSignal)
                codeBuilder.aload(thisSlot)
                codeBuilder.aload(afterShutdownSignalSlot)
                codeBuilder.invokedynamic(createLambda(
                    interfaceMethod = Runnable::run,
                    targetType = CD_BContextImpl,
                    targetMethod = "doScheduleShutdownSignal",
                    targetMethodReturnType = CD_void,
                    targetMethodArguments = listOf(),
                    capturedTypes = listOf(CD_Function0),
                    isStatic = false
                ))
                codeBuilder.astore(doScheduleShutdownSignalSlot)

                // String sessionKey = JDABuilderSession.getCacheKey(this)
                codeBuilder.aload(thisSlot)
                codeBuilder.invokestatic(classDesc<JDABuilderSession>(), "getCacheKey", MethodTypeDesc.of(CD_String, CD_BContext))
                codeBuilder.astore(sessionKeySlot)

                // JDABuilderSession builderSession = JDABuilderSession.getSession(sessionKey)
                codeBuilder.aload(sessionKeySlot)
                codeBuilder.invokestatic(classDesc<JDABuilderSession>(), "getSession", MethodTypeDesc.of(classDesc<JDABuilderSession>(), CD_String))
                codeBuilder.astore(builderSessionSlot)

                // builderSession.onScheduleShutdownSignal(doScheduleShutdownSignal, afterShutdownSignal)
                codeBuilder.aload(builderSessionSlot)
                codeBuilder.aload(doScheduleShutdownSignalSlot)
                codeBuilder.aload(afterShutdownSignalSlot)
                codeBuilder.invokevirtual(classDesc<JDABuilderSession>(), "onScheduleShutdownSignal", MethodTypeDesc.of(CD_void, classDesc<Runnable>(), CD_Function0))

                // Required
                codeBuilder.return_()
            }
        }
    }

    private fun isTargetMethod(methodModel: MethodModel): Boolean {
        if (!methodModel.methodName().equalsString(TARGET_METHOD_NAME)) return false
        if (!methodModel.methodType().equalsString(TARGET_METHOD_SIGNATURE)) return false
        return true
    }

    private companion object {
        const val TARGET_METHOD_NAME = "scheduleShutdownSignal"
        const val TARGET_METHOD_SIGNATURE = "(Lkotlin/jvm/functions/Function0;)V"

        const val LAMBDA_NAME = "doScheduleShutdownSignal"
    }
}
