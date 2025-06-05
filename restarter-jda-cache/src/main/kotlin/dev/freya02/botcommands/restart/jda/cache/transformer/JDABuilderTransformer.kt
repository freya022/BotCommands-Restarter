package dev.freya02.botcommands.restart.jda.cache.transformer

import dev.freya02.botcommands.restart.jda.cache.BufferingEventManager
import dev.freya02.botcommands.restart.jda.cache.JDABuilderConfiguration
import dev.freya02.botcommands.restart.jda.cache.JDABuilderSession
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.classfile.*
import java.lang.classfile.ClassFile.*
import java.lang.constant.*
import java.lang.constant.ConstantDescs.*
import java.lang.invoke.*
import java.lang.reflect.AccessFlag
import java.util.function.Supplier

private val logger = KotlinLogging.logger { }

// Avoid importing BC and JDA classes
private val CD_JDA = ClassDesc.of("net.dv8tion.jda.api.JDA")
private val CD_JDABuilder = ClassDesc.of("net.dv8tion.jda.api.JDABuilder")
private val CD_IEventManager = ClassDesc.of("net.dv8tion.jda.api.hooks.IEventManager")

private val CD_BufferingEventManager = classDesc<BufferingEventManager>()

private val CD_IllegalStateException = ClassDesc.of("java.lang.IllegalStateException")

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
                val builderConfigurationSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)
                val tokenSlot = codeBuilder.parameterSlot(0)
                val intentsSlot = codeBuilder.parameterSlot(1)

                // JDABuilderConfiguration configuration = JDABuilderSession.currentSession().getConfiguration();
                codeBuilder.invokestatic(classDesc<JDABuilderSession>(), "currentSession", MethodTypeDesc.of(classDesc<JDABuilderSession>()))
                codeBuilder.invokevirtual(classDesc<JDABuilderSession>(), "getConfiguration", MethodTypeDesc.of(classDesc<JDABuilderConfiguration>()))
                codeBuilder.astore(builderConfigurationSlot)

                // configuration.onInit(token, intents);
                codeBuilder.aload(builderConfigurationSlot)
                codeBuilder.aload(tokenSlot)
                codeBuilder.iload(intentsSlot)
                codeBuilder.invokevirtual(classDesc<JDABuilderConfiguration>(), "onInit", MethodTypeDesc.of(CD_void, CD_String, CD_int))

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
            MethodTypeDesc.of(CD_JDA),
            ACC_PRIVATE or ACC_SYNTHETIC or ACC_FINAL
        ) { methodBuilder ->
            val codeModel = methodModel.code().get()

            methodBuilder.withCode { codeBuilder ->
                val thisSlot = codeBuilder.receiverSlot()

                val bufferingEventManagerSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)

                // JDABuilder's eventManager is null by default,
                // however, the framework mandates setting a framework-provided event manager,
                // so let's just throw if it is null.
                val nullEventManagerLabel = codeBuilder.newLabel()
                codeBuilder.aload(thisSlot)
                codeBuilder.getfield(CD_JDABuilder, "eventManager", CD_IEventManager)
                codeBuilder.ifnull(nullEventManagerLabel)

                // var bufferingEventManager = new BufferingEventManager
                codeBuilder.new_(CD_BufferingEventManager)
                codeBuilder.astore(bufferingEventManagerSlot)

                // bufferingEventManager.<init>(eventManager)
                codeBuilder.aload(bufferingEventManagerSlot)
                codeBuilder.aload(thisSlot)
                codeBuilder.getfield(CD_JDABuilder, "eventManager", CD_IEventManager)
                codeBuilder.invokespecial(CD_BufferingEventManager, "<init>", MethodTypeDesc.of(CD_void, CD_IEventManager))

                // this.setEventManager(eventManager)
                codeBuilder.aload(thisSlot)
                codeBuilder.aload(bufferingEventManagerSlot)
                codeBuilder.invokevirtual(CD_JDABuilder, "setEventManager", MethodTypeDesc.of(CD_JDABuilder, CD_IEventManager))

                // Move the build() code to doBuild()
                codeModel.forEach { codeBuilder.with(it) }

                // Branch when "eventManager" is null
                codeBuilder.labelBinding(nullEventManagerLabel)

                codeBuilder.new_(CD_IllegalStateException)
                codeBuilder.dup()
                codeBuilder.ldc("The event manager must be set using the one provided in JDAService#createJDA" as java.lang.String)
                codeBuilder.invokespecial(CD_IllegalStateException, "<init>", MethodTypeDesc.of(CD_void, CD_String))
                codeBuilder.athrow()
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
                    MethodTypeDesc.of(classDesc<Supplier<*>>(), CD_JDABuilder),
                    // Bootstrap arguments (see `javap -c -v <class file>` from a working .java sample)
                    // This is the 4th argument of LambdaMetafactory#metafactory, "interfaceMethodType",
                    // which is the signature of the implemented method, in this case, Object get()
                    MethodTypeDesc.of(CD_Object),
                    // This is the 5th argument of LambdaMetafactory#metafactory, "implementation",
                    // this is the method to be called when invoking the lambda,
                    // with the captured variables and parameters
                    MethodHandleDesc.ofMethod(
                        DirectMethodHandleDesc.Kind.VIRTUAL,
                        CD_JDABuilder,
                        newBuildMethodName,
                        MethodTypeDesc.of(CD_JDA)
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
                codeBuilder.invokevirtual(classDesc<JDABuilderSession>(), "onBuild", MethodTypeDesc.of(CD_JDA, classDesc<Supplier<*>>()))
                // Again, prefer using a variable for clarity
                codeBuilder.astore(jdaSlot)

                codeBuilder.aload(jdaSlot)
                codeBuilder.areturn()
            }
        }
    }
}

private class PublicInstanceMethodTransform : ClassTransform {

    private val builderSessionMethods: Set<MethodDesc> = ClassFile.of()
        .parse(JDABuilderConfiguration::class.java.getResourceAsStream("JDABuilderConfiguration.class")!!.readAllBytes())
        .methods()
        .mapTo(hashSetOf(), ::MethodDesc)

    override fun accept(classBuilder: ClassBuilder, classElement: ClassElement) {
        val methodModel = classElement as? MethodModel ?: return classBuilder.retain(classElement)
        if (!methodModel.flags().has(AccessFlag.PUBLIC)) return classBuilder.retain(classElement)
        if (methodModel.flags().has(AccessFlag.STATIC)) return classBuilder.retain(classElement)

        logger.trace { "Transforming ${methodModel.methodName().stringValue()}" }

        classBuilder.transformMethod(methodModel) { methodBuilder, methodElement ->
            val codeModel = methodElement as? CodeModel ?: return@transformMethod methodBuilder.retain(methodElement)

            val hasBuilderSessionMethod = methodModel.let(::MethodDesc) in builderSessionMethods
            methodBuilder.withCode { codeBuilder ->
                val builderConfigurationSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)

                // JDABuilderConfiguration configuration = JDABuilderSession.currentSession().getConfiguration();
                codeBuilder.invokestatic(classDesc<JDABuilderSession>(), "currentSession", MethodTypeDesc.of(classDesc<JDABuilderSession>()))
                codeBuilder.invokevirtual(classDesc<JDABuilderSession>(), "getConfiguration", MethodTypeDesc.of(classDesc<JDABuilderConfiguration>()))
                codeBuilder.astore(builderConfigurationSlot)

                if (hasBuilderSessionMethod) {
                    logger.trace { "Registering $methodModel as a cache-compatible method" }

                    val methodName = methodModel.methodName().stringValue()
                    // Set return type to "void" because our method won't return JDABuilder, and it doesn't matter anyway
                    val methodType = methodModel.methodTypeSymbol().changeReturnType(CD_void)

                    // configuration.theMethod(parameters);
                    codeBuilder.aload(builderConfigurationSlot)
                    methodType.parameterList().forEachIndexed { index, parameter ->
                        val typeKind = TypeKind.fromDescriptor(parameter.descriptorString())
                        val slot = codeBuilder.parameterSlot(index)
                        codeBuilder.loadLocal(typeKind, slot)
                    }
                    codeBuilder.invokevirtual(classDesc<JDABuilderConfiguration>(), methodName, methodType)
                } else {
                    logger.trace { "Skipping $methodModel as it does not have an equivalent method handler" }

                    // configuration.markUnsupportedValue()
                    codeBuilder.aload(builderConfigurationSlot)
                    codeBuilder.invokevirtual(classDesc<JDABuilderConfiguration>(), "markUnsupportedValue", MethodTypeDesc.of(CD_void))
                }

                // Add existing instructions
                codeModel.forEach { codeBuilder.with(it) }
            }
        }
    }

    // Utility to match methods using their name and parameters, but not return type
    private data class MethodDesc(
        val name: String,
        val paramTypes: List<ClassDesc>
    ) {
        constructor(methodModel: MethodModel) : this(
            methodModel.methodName().stringValue(),
            methodModel.methodTypeSymbol().parameterList(),
        )
    }
}