package dev.freya02.botcommands.restart.jda.cache.transformer

import dev.freya02.botcommands.restart.jda.cache.JDABuilderSession
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.classfile.*
import java.lang.classfile.ClassFile.*
import java.lang.constant.*
import java.lang.constant.ConstantDescs.CD_String
import java.lang.constant.ConstantDescs.CD_void
import java.lang.invoke.*

private val logger = KotlinLogging.logger { }

// Avoid importing BC and JDA classes
private val CD_JDAImpl = ClassDesc.of("net.dv8tion.jda.internal.JDAImpl")

internal object JDAImplTransformer : AbstractClassFileTransformer("net/dv8tion/jda/internal/JDAImpl") {

    override fun transform(classData: ByteArray): ByteArray {
        val classFile = ClassFile.of()
        return classFile.transformClass(
            classFile.parse(classData),
            ShutdownTransform()
        )
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

        logger.trace { "Transforming JDABuilder's build() method" }

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

                val builderSessionSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)
                val doShutdownSlot = codeBuilder.allocateLocal(TypeKind.REFERENCE)

                // Runnable doShutdown = this::doShutdown
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
                    "run",
                    // This is the 3rd argument of LambdaMetafactory#metafactory, "factoryType",
                    // the return type is the implemented interface,
                    // while the parameters are the captured variables (incl. receiver)
                    MethodTypeDesc.of(classDesc<Runnable>(), CD_JDAImpl),
                    // Bootstrap arguments (see `javap -c -v <class file>` from a working .java sample)
                    // This is the 4th argument of LambdaMetafactory#metafactory, "interfaceMethodType",
                    // which is the signature of the implemented method, in this case, void run()
                    MethodTypeDesc.of(CD_void),
                    // This is the 5th argument of LambdaMetafactory#metafactory, "implementation",
                    // this is the method to be called when invoking the lambda,
                    // with the captured variables and parameters
                    MethodHandleDesc.ofMethod(
                        DirectMethodHandleDesc.Kind.VIRTUAL,
                        CD_JDAImpl,
                        newShutdownMethodName,
                        MethodTypeDesc.of(CD_void)
                    ),
                    // This is the 6th argument of LambdaMetafactory#metafactory, "dynamicMethodType",
                    // this is "the signature and return type to be enforced dynamically at invocation type"
                    // This is usually the same as "interfaceMethodType"
                    MethodTypeDesc.of(CD_void),
                ))
                codeBuilder.astore(doShutdownSlot)

                // JDABuilderSession session = JDABuilderSession.currentSession();
                codeBuilder.invokestatic(classDesc<JDABuilderSession>(), "currentSession", MethodTypeDesc.of(classDesc<JDABuilderSession>()))
                codeBuilder.astore(builderSessionSlot)

                // session.onShutdown(this::doShutdown);
                codeBuilder.aload(builderSessionSlot)
                codeBuilder.aload(doShutdownSlot)
                codeBuilder.invokevirtual(classDesc<JDABuilderSession>(), "onShutdown", MethodTypeDesc.of(CD_void, classDesc<Runnable>()))

                codeBuilder.return_()
            }
        }
    }
}