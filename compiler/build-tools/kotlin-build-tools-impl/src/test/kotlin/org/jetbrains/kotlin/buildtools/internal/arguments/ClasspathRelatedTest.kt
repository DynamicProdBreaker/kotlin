/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.buildtools.internal.arguments

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createTempFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

internal class ClasspathRelatedTest {

    private lateinit var tempFile1: Path
    private lateinit var tempFile2: Path

    @BeforeTest
    fun setUp() {
        tempFile1 = createTempFile()
        tempFile2 = createTempFile()
    }

    @AfterTest
    fun tearDown() {
        tempFile1.toFile().delete()
        tempFile2.toFile().delete()
    }

    @Test
    fun `when multiple paths provided, then classpath string is set with path separator`() {
        val args = K2JVMCompilerArguments()

        args.applyClasspath(listOf(tempFile1, tempFile2))

        val expected = "${tempFile1.toAbsolutePath()}${File.pathSeparator}${tempFile2.toAbsolutePath()}"
        assertEquals(expected, args.classpath)
    }

    @Test
    fun `when empty list provided, then classpath is not set`() {
        val args = K2JVMCompilerArguments()

        args.applyClasspath(emptyList())

        assertEquals(null, args.classpath)
    }

    @Test
    fun `when single path provided, then classpath is set correctly`() {
        val args = K2JVMCompilerArguments()

        args.applyClasspath(listOf(tempFile1))

        assertEquals(tempFile1.toAbsolutePath().toString(), args.classpath)
    }

    @Test
    fun `when compiler args contain classpath, then parsed paths are returned`() {
        val args = K2JVMCompilerArguments().apply {
            classpath = "${tempFile1.toAbsolutePath()}${File.pathSeparator}${tempFile2.toAbsolutePath()}"
        }

        val result = applyClasspath(emptyList(), args)

        assertEquals(2, result.size)
        assertEquals(tempFile1.toAbsolutePath(), result[0].toAbsolutePath())
        assertEquals(tempFile2.toAbsolutePath(), result[1].toAbsolutePath())
    }

    @Test
    fun `when compiler args classpath is null, then empty list is returned`() {
        val args = K2JVMCompilerArguments().apply {
            classpath = null
        }

        val result = applyClasspath(listOf(Path("dummy")), args)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `when compiler args classpath is empty, then empty list is returned`() {
        val args = K2JVMCompilerArguments().apply {
            classpath = ""
        }

        val result = applyClasspath(emptyList(), args)

        assertEquals(emptyList(), result)
    }
}
