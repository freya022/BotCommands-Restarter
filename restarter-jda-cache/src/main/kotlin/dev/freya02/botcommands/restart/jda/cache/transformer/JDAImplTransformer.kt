package dev.freya02.botcommands.restart.jda.cache.transformer

import dev.freya02.botcommands.restart.jda.cache.JDABuilderSession
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.classfile.*
import java.lang.classfile.ClassFile.*
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_String
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.MethodTypeDesc

private val logger = KotlinLogging.logger { }

// Avoid importing BC and JDA classes
private val CD_JDA = ClassDesc.of("net.dv8tion.jda.api.JDA")
private val CD_JDAImpl = ClassDesc.of("net.dv8tion.jda.internal.JDAImpl")
private val CD_JDABuilderSession = classDesc<JDABuilderSession>()

private const val cacheKeyFieldName = "cacheKey"

internal object JDAImplTransformer : AbstractClassFileTransformer("net/dv8tion/jda/internal/JDAImpl") {

    override fun transform(classData: ByteArray): ByteArray {
        val classFile = ClassFile.of()
        val classModel = classFile.parse(classData)
        return classFile.build(classModel.thisClass().asSymbol()) { classBuilder ->
            classBuilder.withField(cacheKeyFieldName, CD_String, ACC_PRIVATE or ACC_FINAL)

            classBuilder.withMethod("getBuilderSession", MethodTypeDesc.of(CD_JDABuilderSession), ACC_PUBLIC) { methodBuilder ->
                methodBuilder.withCode { codeBuilder ->
                    codeBuilder.aload(codeBuilder.receiverSlot())
                    codeBuilder.getfield(CD_JDAImpl, cacheKeyFieldName, CD_String)
                    codeBuilder.invokestatic(CD_JDABuilderSession, "getSession", MethodTypeDesc.of(CD_JDABuilderSession, CD_String))
                    codeBuilder.areturn()
                }
            }

            val transform = CaptureSessionKeyTransform()
                .andThen(ShutdownTransform())
                .andThen(ShutdownNowTransform())
                .andThen(AwaitShutdownTransform())
            classBuilder.transform(classModel, transform)
        }
    }
}

private class CaptureSessionKeyTransform : ClassTransform {

    override fun accept(classBuilder: ClassBuilder, classElement: ClassElement) {
        val methodModel = classElement as? MethodModel ?: return classBuilder.retain(classElement)
        if (!methodModel.methodName().equalsString("<init>")) return classBuilder.retain(classElement)

        // No need to check the signature, we can assign the field in all constructors

        logger.trace { "Transforming (one of) JDAImpl's constructor" }

        classBuilder.transformMethod(methodModel) { methodBuilder, methodElement ->
            val codeModel = methodElement as? CodeModel ?: return@transformMethod methodBuilder.retain(methodElement)

            methodBuilder.withCode { codeBuilder ->
                val thisSlot = codeBuilder.receiverSlot()

                // this.cacheKey = JDABuilderSession.currentSession().getKey()
                codeBuilder.aload(thisSlot)
                codeBuilder.invokestatic(CD_JDABuilderSession, "currentSession", MethodTypeDesc.of(CD_JDABuilderSession))
                codeBuilder.invokevirtual(CD_JDABuilderSession, "getKey", MethodTypeDesc.of(CD_String))
                codeBuilder.putfield(CD_JDAImpl, cacheKeyFieldName, CD_String)

                // Add existing instructions
                codeModel.forEach { codeBuilder.with(it) }
            }
        }
    }
}

private class ShutdownTransform : ClassTransform {

    override fun accept(classBuilder: ClassBuilder, classElement: ClassElement) {
        val methodModel = classElement as? MethodModel ?: return classBuilder.retain(classElement)
        if (!methodModel.methodName().equalsString("shutdown")) return classBuilder.retain(classElement)

        val methodType = methodModel.methodTypeSymbol()
        if (methodType.parameterList() != emptyList<ClassDesc>()) {
            // TODO not sure about the exception model yet,
            //  maybe we should just disable the JDA cache instead of being completely incompatible
            throw IllegalArgumentException("Incompatible JDAImpl shutdown method: $methodType")
        }

        logger.trace { "Transforming JDABuilder's shutdown() method" }

        val newShutdownMethodName = "doShutdown"
        classBuilder.withMethod(
            newShutdownMethodName,
            MethodTypeDesc.of(CD_void),
            ACC_PRIVATE or ACC_SYNTHETIC or ACC_FINAL
        ) { methodBuilder ->
            val codeModel = methodModel.code().get()

            methodBuilder.withCode { codeBuilder ->
                // Move the shutdown() code to doShutdown()
                codeModel.forEach { codeBuilder.with(it) }
            }
        }

        classBuilder.transformMethod(methodModel) { methodBuilder, methodElement ->
            if (methodElement !is CodeModel) return@transformMethod methodBuilder.retain(methodElement)

            methodBuilder.withCode { codeBuilder ->
                val thisSlot = codeBuilder.receiverSlot()

                val doShutdownSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)
                val builderSessionSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)

                // Runnable doShutdown = this::doShutdown
                codeBuilder.aload(thisSlot)
                codeBuilder.invokedynamic(createLambda(
                    interfaceMethod = Runnable::run,
                    targetType = CD_JDAImpl,
                    targetMethod = newShutdownMethodName,
                    targetMethodReturnType = CD_void,
                    targetMethodArguments = listOf(),
                    capturedTypes = listOf(),
                    isStatic = false
                ))
                codeBuilder.astore(doShutdownSlot)

                // var builderSession = getBuilderSession()
                codeBuilder.aload(thisSlot)
                codeBuilder.invokevirtual(CD_JDAImpl, "getBuilderSession", MethodTypeDesc.of(CD_JDABuilderSession))
                codeBuilder.astore(builderSessionSlot)

                // builderSession.onShutdown(this, this::doShutdown);
                codeBuilder.aload(builderSessionSlot)
                codeBuilder.aload(thisSlot)
                codeBuilder.aload(doShutdownSlot)
                codeBuilder.invokevirtual(CD_JDABuilderSession, "onShutdown", MethodTypeDesc.of(CD_void, CD_JDA, classDesc<Runnable>()))

                codeBuilder.return_()
            }
        }
    }
}

private class ShutdownNowTransform : ClassTransform {

    override fun accept(classBuilder: ClassBuilder, classElement: ClassElement) {
        val methodModel = classElement as? MethodModel ?: return classBuilder.retain(classElement)
        if (!methodModel.methodName().equalsString("shutdownNow")) return classBuilder.retain(classElement)

        val methodType = methodModel.methodTypeSymbol()
        if (methodType.parameterList() != emptyList<ClassDesc>()) {
            // TODO not sure about the exception model yet,
            //  maybe we should just disable the JDA cache instead of being completely incompatible
            throw IllegalArgumentException("Incompatible JDAImpl shutdownNow method: $methodType")
        }

        logger.trace { "Transforming JDABuilder's shutdownNow() method" }

        val newShutdownMethodName = "doShutdownNow"
        classBuilder.withMethod(
            newShutdownMethodName,
            MethodTypeDesc.of(CD_void),
            ACC_PRIVATE or ACC_SYNTHETIC or ACC_FINAL
        ) { methodBuilder ->
            val codeModel = methodModel.code().get()

            methodBuilder.withCode { codeBuilder ->
                // Move the shutdownNow() code to doShutdownNow()
                codeModel.forEach { codeElement ->
                    // Replace shutdown() with doShutdown() so we don't call [[JDABuilderSession#onShutdown]] more than once
                    if (codeElement is InvokeInstruction && codeElement.name().equalsString("shutdown")) {
                        require(codeElement.type().equalsString("()V"))
                        codeBuilder.invokevirtual(codeElement.owner().asSymbol(), "doShutdown", MethodTypeDesc.of(CD_void))
                        return@forEach
                    }

                    codeBuilder.with(codeElement)
                }
            }
        }

        classBuilder.transformMethod(methodModel) { methodBuilder, methodElement ->
            if (methodElement !is CodeModel) return@transformMethod methodBuilder.retain(methodElement)

            methodBuilder.withCode { codeBuilder ->
                val thisSlot = codeBuilder.receiverSlot()

                val doShutdownNowSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)
                val builderSessionSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)

                // Runnable doShutdownNow = this::doShutdownNow
                codeBuilder.aload(thisSlot)
                codeBuilder.invokedynamic(createLambda(
                    interfaceMethod = Runnable::run,
                    targetType = CD_JDAImpl,
                    targetMethod = newShutdownMethodName,
                    targetMethodReturnType = CD_void,
                    targetMethodArguments = listOf(),
                    capturedTypes = listOf(),
                    isStatic = false
                ))
                codeBuilder.astore(doShutdownNowSlot)

                // var builderSession = getBuilderSession()
                codeBuilder.aload(thisSlot)
                codeBuilder.invokevirtual(CD_JDAImpl, "getBuilderSession", MethodTypeDesc.of(CD_JDABuilderSession))
                codeBuilder.astore(builderSessionSlot)

                // builderSession.onShutdown(this, this::doShutdownNow);
                codeBuilder.aload(builderSessionSlot)
                codeBuilder.aload(thisSlot)
                codeBuilder.aload(doShutdownNowSlot)
                codeBuilder.invokevirtual(CD_JDABuilderSession, "onShutdown", MethodTypeDesc.of(CD_void, CD_JDA, classDesc<Runnable>()))

                codeBuilder.return_()
            }
        }
    }
}

private class AwaitShutdownTransform : ClassTransform {

    override fun accept(classBuilder: ClassBuilder, classElement: ClassElement) {
        val methodModel = classElement as? MethodModel ?: return classBuilder.retain(classElement)
        if (!methodModel.methodName().equalsString("awaitShutdown")) return classBuilder.retain(classElement)

        logger.trace { "Transforming (one of) JDA's awaitShutdown method" }

        classBuilder.transformMethod(methodModel) { methodBuilder, methodElement ->
            if (methodElement !is CodeModel) return@transformMethod methodBuilder.retain(methodElement)

            methodBuilder.withCode { codeBuilder ->
                codeBuilder.iconst_0()
                codeBuilder.ireturn()
            }
        }
    }
}