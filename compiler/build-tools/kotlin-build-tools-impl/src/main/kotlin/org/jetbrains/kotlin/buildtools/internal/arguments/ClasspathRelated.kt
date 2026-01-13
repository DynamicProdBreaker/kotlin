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

internal fun K2JVMCompilerArguments.applyClasspath(classpath: List<Path>) {
    this.classpath = classpath.joinToString(File.pathSeparator) { it.absolutePathString() }
}

internal fun applyClasspath(
    currentValue: List<Path>,
    compilerArgs: K2JVMCompilerArguments,
): List<Path> {
    val rawValue = compilerArgs.classpath?.split(File.pathSeparator)?.map(::Path) ?: emptyList()

    return rawValue
}