package dev.freya02.botcommands.restart.jda.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.classfile.*
import java.lang.classfile.ClassFile.*
import java.lang.constant.*
import java.lang.constant.ConstantDescs.*
import java.lang.invoke.*
import java.lang.reflect.AccessFlag
import java.util.function.Supplier

private val logger = KotlinLogging.logger { }

private val JDADesc = ClassDesc.of("net.dv8tion.jda.api.JDA")
private val JDABuilderDesc = ClassDesc.of("net.dv8tion.jda.api.JDABuilder")

internal object JDABuilderTransformer : AbstractClassFileTransformer("net/dv8tion/jda/api/JDABuilder") {

    override fun transform(classData: ByteArray): ByteArray {
        val classFile = ClassFile.of()
        return classFile.transformClass(
            classFile.parse(classData),
            PublicInstanceMethodTransform()
                .andThen(ConstructorTransform())
                .andThen(BuildTransform())
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

private class BuildTransform : ClassTransform {

    override fun accept(classBuilder: ClassBuilder, classElement: ClassElement) {
        val methodModel = classElement as? MethodModel ?: return classBuilder.retain(classElement)
        if (!methodModel.methodName().equalsString("build")) return classBuilder.retain(classElement)

        val methodType = methodModel.methodTypeSymbol()
        if (methodType.parameterList() != emptyList<ClassDesc>()) {
            // TODO not sure about the exception model yet,
            //  maybe we should just disable the JDA cache instead of being completely incompatible
            throw IllegalArgumentException("Incompatible JDABuilder build method: $methodType")
        }

        logger.trace { "Transforming JDABuilder's build() method" }

        val newBuildMethodName = "doBuild"
        classBuilder.withMethod(
            newBuildMethodName,
            MethodTypeDesc.of(JDADesc),
            ACC_PRIVATE or ACC_SYNTHETIC or ACC_FINAL
        ) { methodBuilder ->
            val codeModel = methodModel.code().get()

            methodBuilder.withCode { codeBuilder ->
                // Move the build() code to doBuild()
                codeModel.forEach { codeBuilder.with(it) }
            }
        }

        classBuilder.transformMethod(methodModel) { methodBuilder, methodElement ->
            if (methodElement !is CodeModel) return@transformMethod methodBuilder.retain(methodElement)

            methodBuilder.withCode { codeBuilder ->
                val thisSlot = codeBuilder.receiverSlot()

                val builderSessionSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)
                val doBuildSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)
                val jdaSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)

                // Supplier<JDA> doBuild = this::doBuild
                codeBuilder.aload(thisSlot)
                codeBuilder.invokedynamic(DynamicCallSiteDesc.of(
                    MethodHandleDesc.ofMethod(
                        DirectMethodHandleDesc.Kind.STATIC,
                        classDesc<LambdaMetafactory>(),
                        "metafactory",
                        MethodTypeDesc.of(classDesc<CallSite>(), classDesc<MethodHandles.Lookup>(), CD_String, classDesc<MethodType>(), classDesc<MethodType>(), classDesc<MethodHandle>(), classDesc<MethodType>())
                    ),
                    // The following parameters are from [[LambdaMetafactory#metafactory]]
                    // This is the 2nd argument of LambdaMetafactory#metafactory, "interfaceMethodName",
                    // the method name in Supplier is "get"
                    "get",
                    // This is the 3rd argument of LambdaMetafactory#metafactory, "factoryType",
                    // the return type is the implemented interface,
                    // while the parameters are the captured variables (incl. receiver)
                    MethodTypeDesc.of(classDesc<Supplier<*>>(), JDABuilderDesc),
                    // Bootstrap arguments (see `javap -c -v <class file>` from a working .java sample)
                    // This is the 4th argument of LambdaMetafactory#metafactory, "interfaceMethodType",
                    // which is the signature of the implemented method, in this case, Object get()
                    MethodTypeDesc.of(CD_Object),
                    // This is the 5th argument of LambdaMetafactory#metafactory, "implementation",
                    // this is the method to be called when invoking the lambda,
                    // with the captured variables and parameters
                    MethodHandleDesc.ofMethod(
                        DirectMethodHandleDesc.Kind.VIRTUAL,
                        JDABuilderDesc,
                        newBuildMethodName,
                        MethodTypeDesc.of(JDADesc)
                    ),
                    // This is the 6th argument of LambdaMetafactory#metafactory, "dynamicMethodType",
                    // this is "the signature and return type to be enforced dynamically at invocation type"
                    // This is usually the same as "interfaceMethodType"
                    MethodTypeDesc.of(CD_Object),
                ))
                codeBuilder.astore(doBuildSlot)

                // JDABuilderSession session = JDABuilderSession.currentSession();
                codeBuilder.invokestatic(classDesc<JDABuilderSession>(), "currentSession", MethodTypeDesc.of(classDesc<JDABuilderSession>()))
                codeBuilder.astore(builderSessionSlot)

                // var jda = session.onBuild(this::doBuild);
                codeBuilder.aload(builderSessionSlot)
                codeBuilder.aload(doBuildSlot)
                codeBuilder.invokevirtual(classDesc<JDABuilderSession>(), "onBuild", MethodTypeDesc.of(JDADesc, classDesc<Supplier<*>>()))
                // Again, prefer using a variable for clarity
                codeBuilder.astore(jdaSlot)

                codeBuilder.aload(jdaSlot)
                codeBuilder.areturn()
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