package dev.freya02.botcommands.restart.jda.cache.transformer

import java.lang.classfile.ClassFileBuilder
import java.lang.classfile.ClassFileElement
import java.lang.constant.ClassDesc

internal inline fun <reified T : Any> classDesc(): ClassDesc = ClassDesc.of(T::class.java.name)

internal fun <E : ClassFileElement> ClassFileBuilder<E, *>.retain(element: E) {
    with(element)
}