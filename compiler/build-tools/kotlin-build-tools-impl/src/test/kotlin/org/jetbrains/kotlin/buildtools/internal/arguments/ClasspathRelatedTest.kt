/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.buildtools.internal.arguments

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
internal class ClasspathRelatedTest(
    @Suppress("unused") typeDescription: String,
    private val classpathProvider: (List<Path>) -> Any?,
    private val classpathToStringProvider: (Any) -> String,
) {

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
    fun `when multiple paths provided, then classpath is set correctly`() {
        val args = K2JVMCompilerArguments()
        val paths = listOf(tempFile1, tempFile2)

        args.applyClasspath(classpathProvider(paths))

        val expected = "${tempFile1.toAbsolutePath()}${File.pathSeparator}${tempFile2.toAbsolutePath()}"
        assertEquals(expected, args.classpath)
    }

    @Test
    fun `when empty classpath provided, then classpath is set to empty`() {
        val args = K2JVMCompilerArguments()

        args.applyClasspath(classpathProvider(emptyList()))

        assertEquals(null, args.classpath)
    }

    @Test
    fun `when single path provided, then classpath is set correctly`() {
        val args = K2JVMCompilerArguments()
        val paths = listOf(tempFile1)

        args.applyClasspath(classpathProvider(paths))

        assertEquals(tempFile1.toAbsolutePath().toString(), args.classpath)
    }

    @Test
    fun `when compiler args contain classpath, then values are returned correctly`() {
        val args = K2JVMCompilerArguments().apply {
            classpath = "${tempFile1.toAbsolutePath()}${File.pathSeparator}${tempFile2.toAbsolutePath()}"
        }

        val result = applyClasspath(classpathProvider(emptyList()), args)

        assertEquals(args.classpath, classpathToStringProvider(result))
    }

    @Test
    fun `when compiler args classpath is null, then empty value is returned`() {
        val args = K2JVMCompilerArguments().apply {
            classpath = null
        }

        val result = applyClasspath(classpathProvider(listOf(Path("dummy"))), args)

        assertEquals("", classpathToStringProvider(result))
    }

    @Test
    fun `when compiler args classpath is empty string, then empty value is returned`() {
        val args = K2JVMCompilerArguments().apply {
            classpath = ""
        }

        val result = applyClasspath(classpathProvider(emptyList()), args)

        assertEquals("", classpathToStringProvider(result))
    }

    companion object {

        private val LIST_TYPE_SIMPLE_NAME = List::class.java.simpleName
        private val STRING_TYPE_SIMPLE_NAME = String::class.java.simpleName

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf(
                LIST_TYPE_SIMPLE_NAME,
                { paths: List<Path> -> paths },
                { paths: List<Path> -> paths.joinToString(File.pathSeparator) { it.absolutePathString() } },
            ),
            arrayOf(
                STRING_TYPE_SIMPLE_NAME,
                { paths: List<Path> -> paths.joinToString(File.pathSeparator) { it.absolutePathString() } },
                { string: String? -> string ?: "" }
            )
        )
    }
}
