/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("FunctionName")

package org.jetbrains.kotlin.gradle.unitTests

import org.gradle.api.Project
import org.gradle.api.file.ProjectLayout
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dependencyResolutionTests.configureRepositoriesForTests
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.diagnostics.KotlinToolingDiagnostics
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.GenerateSyntheticLinkageImportProject
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftImportExtension
import org.jetbrains.kotlin.gradle.util.*
import org.jetbrains.kotlin.konan.target.HostManager
import org.junit.Assume
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SwiftPMImportUnitTests {

    @Test
    fun `test local package with relative path configures task correctly`() {
        Assume.assumeTrue("macOS host required for this test", HostManager.hostIsMac)

        val project = swiftPMImportProject(
            preApplyCode = {
                // Create local Swift package directory with Package.swift
                val localPackageDir = projectDir.resolve("localSwiftPackage")
                localPackageDir.mkdirs()
                localPackageDir.resolve("Package.swift").writeText(
                    """
                    // swift-tools-version: 5.9
                    import PackageDescription
                    let package = Package(name: "LocalSwiftPackage")
                    """.trimIndent()
                )
            },
            swiftPMDependencies = { layout ->
                localPackage(
                    directory = layout.projectDirectory.dir("localSwiftPackage"),
                    products = listOf("LocalSwiftPackage"),
                )
            }
        )

        project.evaluate()

        // Verify task is registered
        val task = project.tasks.findByName("generateSyntheticLinkageSwiftPMImportProjectForCinteropsAndLdDump")
        assertNotNull(task, "generateSyntheticLinkageSwiftPMImportProjectForCinteropsAndLdDump task should be registered")
        assertTrue(task is GenerateSyntheticLinkageImportProject)

        // Verify projectDirectory property is set (configuration cache fix)
        val projectDirectory = (task as GenerateSyntheticLinkageImportProject).projectDirectory.get().asFile
        assertEquals(project.projectDir, projectDirectory, "projectDirectory should be set to project.projectDir")

        // Verify no error diagnostics
        project.assertNoDiagnostics(KotlinToolingDiagnostics.SwiftPMLocalPackageDirectoryNotFound)
        project.assertNoDiagnostics(KotlinToolingDiagnostics.SwiftPMLocalPackageMissingManifest)
        project.assertNoDiagnostics(KotlinToolingDiagnostics.SwiftPMLocalPackageInvalidName)
    }

    @Test
    fun `test local package name inference from directory`() {
        Assume.assumeTrue("macOS host required for this test", HostManager.hostIsMac)

        val project = swiftPMImportProject(
            preApplyCode = {
                // Create local Swift package directory with Package.swift
                val localPackageDir = projectDir.resolve("MyCustomPackage")
                localPackageDir.mkdirs()
                localPackageDir.resolve("Package.swift").writeText(
                    """
                    // swift-tools-version: 5.9
                    import PackageDescription
                    let package = Package(name: "MyCustomPackage")
                    """.trimIndent()
                )
            },
            swiftPMDependencies = { layout ->
                // Don't specify packageName - should be inferred from directory name
                localPackage(
                    directory = layout.projectDirectory.dir("MyCustomPackage"),
                    products = listOf("MyCustomPackage"),
                )
            }
        )

        project.evaluate()

        // Verify no error diagnostics - package name was inferred correctly
        project.assertNoDiagnostics(KotlinToolingDiagnostics.SwiftPMLocalPackageInvalidName)
        project.assertNoDiagnostics(KotlinToolingDiagnostics.SwiftPMLocalPackageDirectoryNotFound)
        project.assertNoDiagnostics(KotlinToolingDiagnostics.SwiftPMLocalPackageMissingManifest)

        // Verify task is registered (this implicitly validates the package was configured)
        val task = project.tasks.findByName("generateSyntheticLinkageSwiftPMImportProjectForCinteropsAndLdDump")
        assertNotNull(task, "Task should be registered when local package is configured")
    }

    @Test
    fun `test local package with explicit package name`() {
        Assume.assumeTrue("macOS host required for this test", HostManager.hostIsMac)

        val project = swiftPMImportProject(
            preApplyCode = {
                // Create local Swift package directory with Package.swift
                val localPackageDir = projectDir.resolve("some-directory")
                localPackageDir.mkdirs()
                localPackageDir.resolve("Package.swift").writeText(
                    """
                    // swift-tools-version: 5.9
                    import PackageDescription
                    let package = Package(name: "ActualPackageName")
                    """.trimIndent()
                )
            },
            swiftPMDependencies = { layout ->
                // Specify explicit packageName different from directory name
                localPackage(
                    directory = layout.projectDirectory.dir("some-directory"),
                    products = listOf("ActualPackageName"),
                    packageName = "ExplicitCustomName",
                )
            }
        )

        project.evaluate()

        // Verify no error diagnostics - explicit name is valid
        project.assertNoDiagnostics(KotlinToolingDiagnostics.SwiftPMLocalPackageInvalidName)
        project.assertNoDiagnostics(KotlinToolingDiagnostics.SwiftPMLocalPackageDirectoryNotFound)
        project.assertNoDiagnostics(KotlinToolingDiagnostics.SwiftPMLocalPackageMissingManifest)

        // Verify task is registered
        val task = project.tasks.findByName("generateSyntheticLinkageSwiftPMImportProjectForCinteropsAndLdDump")
        assertNotNull(task, "Task should be registered when local package is configured")
    }

    @Test
    fun `test local package directory not found emits diagnostic`() {
        Assume.assumeTrue("macOS host required for this test", HostManager.hostIsMac)

        val project = swiftPMImportProject(
            swiftPMDependencies = { layout ->
                // Reference a directory that doesn't exist
                localPackage(
                    directory = layout.projectDirectory.dir("nonExistentDirectory"),
                    products = listOf("SomeProduct"),
                )
            }
        )

        project.evaluate()

        // Verify diagnostic is emitted
        project.assertContainsDiagnostic(KotlinToolingDiagnostics.SwiftPMLocalPackageDirectoryNotFound)
    }

    @Test
    fun `test local package missing manifest emits diagnostic`() {
        Assume.assumeTrue("macOS host required for this test", HostManager.hostIsMac)

        val project = swiftPMImportProject(
            preApplyCode = {
                // Create directory but don't create Package.swift
                val localPackageDir = projectDir.resolve("packageWithoutManifest")
                localPackageDir.mkdirs()
                // Intentionally NOT creating Package.swift
            },
            swiftPMDependencies = { layout ->
                localPackage(
                    directory = layout.projectDirectory.dir("packageWithoutManifest"),
                    products = listOf("SomeProduct"),
                )
            }
        )

        project.evaluate()

        // Verify diagnostic is emitted
        project.assertContainsDiagnostic(KotlinToolingDiagnostics.SwiftPMLocalPackageMissingManifest)
    }

    @Test
    fun `test local package with sibling directory path`() {
        Assume.assumeTrue("macOS host required for this test", HostManager.hostIsMac)

        val project = swiftPMImportProject(
            preApplyCode = {
                // Create local Swift package as a sibling directory (using ../)
                val siblingPackageDir = projectDir.parentFile.resolve("siblingSwiftPackage")
                siblingPackageDir.mkdirs()
                siblingPackageDir.resolve("Package.swift").writeText(
                    """
                    // swift-tools-version: 5.9
                    import PackageDescription
                    let package = Package(name: "SiblingSwiftPackage")
                    """.trimIndent()
                )
            },
            swiftPMDependencies = { layout ->
                localPackage(
                    directory = layout.projectDirectory.dir("../siblingSwiftPackage"),
                    products = listOf("SiblingSwiftPackage"),
                )
            }
        )

        project.evaluate()

        // Verify no error diagnostics
        project.assertNoDiagnostics(KotlinToolingDiagnostics.SwiftPMLocalPackageDirectoryNotFound)
        project.assertNoDiagnostics(KotlinToolingDiagnostics.SwiftPMLocalPackageMissingManifest)
        project.assertNoDiagnostics(KotlinToolingDiagnostics.SwiftPMLocalPackageInvalidName)

        // Verify task is registered
        val task = project.tasks.findByName("generateSyntheticLinkageSwiftPMImportProjectForCinteropsAndLdDump")
        assertNotNull(task, "Task should be registered when local package is configured with relative path")

        // Clean up sibling directory
        project.projectDir.parentFile.resolve("siblingSwiftPackage").deleteRecursively()
    }
}

private fun swiftPMImportProject(
    projectBuilder: ProjectBuilder.() -> Unit = {},
    preApplyCode: Project.() -> Unit = {},
    multiplatform: KotlinMultiplatformExtension.() -> Unit = {
        iosSimulatorArm64()
    },
    swiftPMDependencies: SwiftImportExtension.(ProjectLayout) -> Unit = {},
): ProjectInternal = buildProjectWithMPP(
    projectBuilder = projectBuilder,
    preApplyCode = {
        configureRepositoriesForTests()
        preApplyCode()
    },
    code = {
        kotlin {
            multiplatform()
            swiftImport {
                swiftPMDependencies(this@kotlin.project.layout)
            }
        }
    }
)
