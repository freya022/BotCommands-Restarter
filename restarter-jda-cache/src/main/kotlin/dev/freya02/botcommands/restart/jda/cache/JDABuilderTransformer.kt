package dev.freya02.botcommands.restart.jda.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.classfile.*
import java.lang.constant.ConstantDescs.*
import java.lang.constant.MethodTypeDesc
import java.lang.reflect.AccessFlag

private val logger = KotlinLogging.logger { }

internal object JDABuilderTransformer : AbstractClassFileTransformer("net/dv8tion/jda/api/JDABuilder") {

    override fun transform(classData: ByteArray): ByteArray {
        val classFile = ClassFile.of()
        return classFile.transformClass(
            classFile.parse(classData),
            PublicInstanceMethodTransform()
                .andThen(ConstructorTransform())
        )
    }
}

private class ConstructorTransform : ClassTransform {

    override fun accept(classBuilder: ClassBuilder, classElement: ClassElement) {
        val methodModel = classElement as? MethodModel ?: return classBuilder.retain(classElement)
        if (!methodModel.methodName().equalsString("<init>")) return classBuilder.retain(classElement)

        val methodType = methodModel.methodTypeSymbol()
        if (methodType.parameterList() != listOf(CD_String, CD_int)) {
            // TODO not sure about the exception model yet,
            //  maybe we should just disable the JDA cache instead of being completely incompatible
            throw IllegalArgumentException("Incompatible JDABuilder constructor: $methodType")
        }

        logger.trace { "Transforming JDABuilder's constructor" }

        classBuilder.transformMethod(methodModel) { methodBuilder, methodElement ->
            val codeModel = methodElement as? CodeModel ?: return@transformMethod methodBuilder.retain(methodElement)

            methodBuilder.withCode { codeBuilder ->
                val builderSessionSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)
                val tokenSlot = codeBuilder.parameterSlot(0)
                val intentsSlot = codeBuilder.parameterSlot(1)

                // JDABuilderSession session = JDABuilderSession.currentSession();
                codeBuilder.invokestatic(classDesc<JDABuilderSession>(), "currentSession", MethodTypeDesc.of(classDesc<JDABuilderSession>()))
                codeBuilder.astore(builderSessionSlot)

                // session.onInit(token, intents);
                codeBuilder.aload(builderSessionSlot)
                codeBuilder.aload(tokenSlot)
                codeBuilder.iload(intentsSlot)
                codeBuilder.invokevirtual(classDesc<JDABuilderSession>(), "onInit", MethodTypeDesc.of(CD_void, CD_String, CD_int))

                // Add existing instructions
                codeModel.forEach { codeBuilder.with(it) }
            }
        }
    }
}

private class PublicInstanceMethodTransform : ClassTransform {

    private val builderSessionMethods: Map<MethodTypeDesc, MethodModel> = ClassFile.of()
        .parse(JDABuilderSession::class.java.getResourceAsStream("JDABuilderSession.class")!!.readAllBytes())
        .methods()
        .associateBy { it.methodTypeSymbol() }

    override fun accept(classBuilder: ClassBuilder, classElement: ClassElement) {
        val methodModel = classElement as? MethodModel ?: return classBuilder.retain(classElement)
        if (!methodModel.flags().has(AccessFlag.PUBLIC)) return classBuilder.retain(classElement)
        if (methodModel.flags().has(AccessFlag.STATIC)) return classBuilder.retain(classElement)

        logger.trace { "Transforming ${methodModel.methodName().stringValue()}" }

        classBuilder.transformMethod(methodModel) { methodBuilder, methodElement ->
            val codeModel = methodElement as? CodeModel ?: return@transformMethod methodBuilder.retain(methodElement)

            val hasBuilderSessionMethod = methodModel.methodTypeSymbol().changeReturnType(CD_void) in builderSessionMethods
            methodBuilder.withCode { codeBuilder ->
                val builderSessionSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)

                // JDABuilderSession session = JDABuilderSession.currentSession();
                codeBuilder.invokestatic(classDesc<JDABuilderSession>(), "currentSession", MethodTypeDesc.of(classDesc<JDABuilderSession>()))
                codeBuilder.astore(builderSessionSlot)

                if (hasBuilderSessionMethod) {
                    logger.trace { "Registering $methodModel as a cache-compatible method" }

                    val methodName = methodModel.methodName().stringValue()
                    // Set return type to "void" because our method won't return JDABuilder, and it doesn't matter anyway
                    val methodType = methodModel.methodTypeSymbol().changeReturnType(CD_void)

                    // session.theMethod(parameters);
                    codeBuilder.aload(builderSessionSlot)
                    methodType.parameterList().forEachIndexed { index, parameter ->
                        val typeKind = TypeKind.fromDescriptor(parameter.descriptorString())
                        val slot = codeBuilder.parameterSlot(index)
                        codeBuilder.loadLocal(typeKind, slot)
                    }
                    codeBuilder.invokevirtual(classDesc<JDABuilderSession>(), methodName, methodType)
                } else {
                    logger.trace { "Skipping $methodModel as it does not have an equivalent method handler" }

                    // session.markIncompatible()
                    codeBuilder.aload(builderSessionSlot)
                    codeBuilder.invokevirtual(classDesc<JDABuilderSession>(), "markIncompatible", MethodTypeDesc.of(CD_void))
                }

                // Add existing instructions
                codeModel.forEach { codeBuilder.with(it) }
            }
        }
    }
}