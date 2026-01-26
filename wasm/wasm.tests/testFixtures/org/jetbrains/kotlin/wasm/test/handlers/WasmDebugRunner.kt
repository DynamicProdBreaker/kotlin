/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.wasm.test.handlers

import org.jetbrains.kotlin.test.services.TestServices
import java.io.File

class WasmDebugRunner(testServices: TestServices) : WasmDebugRunnerBase(testServices) {
    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
        if (!someAssertionWasFailed) {
            val artifacts = modulesToArtifact.values.single()
            val outputDirBase = testServices.getWasmTestOutputDirectory()

            val devDir = File(outputDirBase, "dev")
            devDir.mkdirs()
            artifacts.compilation.compilerResult.writeTo(devDir, "index", debugMode, "dev")
            artifacts.compilation.compilationDependencies.forEach {
                it.compilerResult.writeTo(devDir, it.compilerResult.baseFileName, debugMode, "dev")
            }

            val dceDir = File(outputDirBase, "dce")
            dceDir.mkdirs()
            artifacts.dceCompilation?.compilerResult?.writeTo(devDir, "index", debugMode, "dce")
            artifacts.dceCompilation?.compilationDependencies?.forEach {
                it.compilerResult.writeTo(devDir, it.compilerResult.baseFileName, debugMode, "dce")
            }
        }
    }
}