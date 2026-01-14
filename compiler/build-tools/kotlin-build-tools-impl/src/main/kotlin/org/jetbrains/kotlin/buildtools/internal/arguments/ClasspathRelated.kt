/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.buildtools.internal.arguments

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.reflect.jvm.jvmName

@Suppress("UNCHECKED_CAST")
internal fun <T> K2JVMCompilerArguments.applyClasspath(classpath: T?) {
    when (classpath) {
        is String? -> {
            this.classpath = classpath
        }
        is List<*> -> {
            requireAllPaths(classpath)
            applyClasspath(classpath as List<Path>)
        }

        else -> error("Unexpected classpath type: ${classpath::class.jvmName}")
    }
}

private fun K2JVMCompilerArguments.applyClasspath(classpath: List<Path>) {
    this.classpath = classpath.joinToString(File.pathSeparator) { it.absolutePathString() }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> applyClasspath(
    currentValue: T?,
    compilerArgs: K2JVMCompilerArguments,
): T {
    when (currentValue) {
        is String? -> {
            return compilerArgs.classpath as T
        }
        is List<*> -> {
            requireAllPaths(currentValue)
            return applyClasspath(currentValue as List<Path>, compilerArgs) as T
        }

        else -> error("Unexpected classpath type: ${currentValue::class.jvmName}")
    }
}

private fun applyClasspath(
    currentValue: List<Path>,
    compilerArgs: K2JVMCompilerArguments,
): List<Path> {
    val rawValue = compilerArgs.classpath?.split(File.pathSeparator)?.map(::Path) ?: emptyList()

    return rawValue
}

private fun requireAllPaths(classpath: List<*>) {
    require(classpath.all { it is Path }) {
        "Invalid classpath element type: expected Path, but got ${
            classpath.first { it !is Path }?.let { it::class.jvmName } ?: "null"
        }"
    }
}