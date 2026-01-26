/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.wasm.test

import org.jetbrains.kotlin.wasm.test.converters.WasmWholeWorldFirTestMode

open class AbstractFirWasmJsCodegenMultiModuleBoxTest(
    testGroupOutputDirPrefix: String = "codegen/multiModuleBox/",
) : AbstractFirWasmJsTest(
    pathToTestDir = "compiler/testData/codegen/box/",
    testGroupOutputDirPrefix = testGroupOutputDirPrefix
) {
    override val mode: WasmWholeWorldFirTestMode = WasmWholeWorldFirTestMode.MULTI_MODULE
}

open class AbstractFirWasmJsCodegenMultiModuleInteropTest : AbstractFirWasmJsCodegenBoxTest(
    testGroupOutputDirPrefix = "codegen/wasmJsMultiModuleInterop"
) {
    override val mode: WasmWholeWorldFirTestMode = WasmWholeWorldFirTestMode.MULTI_MODULE
}

open class AbstractFirWasmTypeScriptExportMultiModuleTest : AbstractFirWasmTypeScriptExportTest(
    "typescript-export-multi-module/"
) {
    override val mode: WasmWholeWorldFirTestMode = WasmWholeWorldFirTestMode.MULTI_MODULE
}

open class AbstractFirWasmJsMultiModuleSteppingTest(
    testGroupOutputDirPrefix: String = "debug/stepping/firBoxMultiModule",
) : AbstractFirWasmJsTest(
    "compiler/testData/debug/stepping/",
    testGroupOutputDirPrefix
) {
    override val mode: WasmWholeWorldFirTestMode = WasmWholeWorldFirTestMode.MULTI_MODULE
}