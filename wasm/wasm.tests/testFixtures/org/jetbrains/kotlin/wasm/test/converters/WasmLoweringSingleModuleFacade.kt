/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.wasm.test.converters

import org.jetbrains.kotlin.backend.wasm.WasmCompilerResult
import org.jetbrains.kotlin.backend.wasm.compileToLoweredIr
import org.jetbrains.kotlin.cli.pipeline.web.wasm.compileWasmLoweredFragmentsForSingleModule
import org.jetbrains.kotlin.config.perfManager
import org.jetbrains.kotlin.ir.backend.js.MainModule
import org.jetbrains.kotlin.ir.declarations.IdSignatureRetriever
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.test.DebugMode
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.directives.WasmEnvironmentConfigurationDirectives.USE_NEW_EXCEPTION_HANDLING_PROPOSAL
import org.jetbrains.kotlin.test.model.*
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.compilerConfigurationProvider
import org.jetbrains.kotlin.test.services.configuration.WasmEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.defaultsProvider
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.util.PhaseType
import org.jetbrains.kotlin.utils.addToStdlib.applyIf
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue
import org.jetbrains.kotlin.wasm.config.WasmConfigurationKeys
import org.jetbrains.kotlin.wasm.test.handlers.getWasmTestOutputDirectory
import org.jetbrains.kotlin.wasm.test.precompiledKotlinTestNewExceptionsOutputDir
import org.jetbrains.kotlin.wasm.test.precompiledKotlinTestOutputDir
import org.jetbrains.kotlin.wasm.test.precompiledKotlinTestOutputName
import org.jetbrains.kotlin.wasm.test.precompiledStdlibNewExceptionsOutputDir
import org.jetbrains.kotlin.wasm.test.precompiledStdlibOutputDir
import org.jetbrains.kotlin.wasm.test.precompiledStdlibOutputName
import java.io.File

class WasmLoweringSingleModuleFacade(testServices: TestServices) :
    BackendFacade<IrBackendInput, BinaryArtifacts.Wasm>(testServices, BackendKinds.IrBackend, ArtifactKinds.Wasm) {

    private fun patchMjsToRelativePath(mjsContent: String, newExceptionProposal: Boolean): String {
        val outputDirBase = testServices.getWasmTestOutputDirectory()
        fun replacePath(mjsText: String, originalPath: String, replacement: File): String {
            val relativeReplacement =
                replacement.relativeTo(outputDirBase).invariantSeparatorsPath
            val replacedMjsText = mjsText
                .replace(originalPath, relativeReplacement)
            check(replacedMjsText != mjsText) { "Replacement not applied" }
            return replacedMjsText
        }

        val (stdlibOutputDir, testOutputDir) = when (newExceptionProposal) {
            true -> precompiledStdlibNewExceptionsOutputDir to precompiledKotlinTestNewExceptionsOutputDir
            false -> precompiledStdlibOutputDir to precompiledKotlinTestOutputDir
        }

        val stdlibInitFile = File(stdlibOutputDir, "$precompiledStdlibOutputName.uninstantiated.mjs")
        val kotlinTestInitFile = File(testOutputDir, "$precompiledKotlinTestOutputName.uninstantiated.mjs")

        val replacedStdlib = replacePath(
            mjsText = mjsContent,
            originalPath = "./$precompiledStdlibOutputName.uninstantiated.mjs",
            replacement = stdlibInitFile
        )
        val replacedStdlibAndTest = replacePath(
            mjsText = replacedStdlib,
            originalPath = "./$precompiledKotlinTestOutputName.uninstantiated.mjs",
            replacement = kotlinTestInitFile
        )

        return replacedStdlibAndTest
    }

    override fun shouldTransform(module: TestModule): Boolean {
        require(with(testServices.defaultsProvider) { backendKind == inputKind && artifactKind == outputKind })
        return true
    }

    override fun transform(module: TestModule, inputArtifact: IrBackendInput): BinaryArtifacts.Wasm {
        require(inputArtifact is IrBackendInput.WasmDeserializedFromKlibBackendInput)

        val configuration = testServices.compilerConfigurationProvider.getCompilerConfiguration(module)
        val moduleInfo = inputArtifact.moduleInfo

        val mainModule = MainModule.Klib(inputArtifact.klib.absolutePath)

        val testPackage = extractTestPackage(testServices)
        val exportedDeclarations = setOf(FqName.fromSegments(listOfNotNull(testPackage, "box")))
        val performanceManager = configuration.perfManager
        performanceManager?.let {
            it.notifyPhaseFinished(PhaseType.Initialization)
            it.notifyPhaseStarted(PhaseType.TranslationToIr)
        }

        val (allModules, backendContext, _) = compileToLoweredIr(
            moduleInfo,
            mainModule,
            configuration,
            performanceManager = performanceManager,
            exportedDeclarations = exportedDeclarations,
            propertyLazyInitialization = true,
            generateTypeScriptFragment = false,
            disableCrossFileOptimisations = true,
        )

        val debugMode = DebugMode.fromSystemProperty("kotlin.wasm.debugMode")
        val generateWat = debugMode >= DebugMode.DEBUG || configuration.getBoolean(WasmConfigurationKeys.WASM_GENERATE_WAT)

        val useNewExceptionProposal = USE_NEW_EXCEPTION_HANDLING_PROPOSAL in testServices.moduleStructure.allDirectives

        val isMainModule = WasmEnvironmentConfigurator.isMainModule(module, testServices)

        val compilerResult = compileWasmLoweredFragmentsForSingleModule(
            configuration = configuration,
            loweredIrFragments = allModules,
            backendContext = backendContext,
            signatureRetriever = moduleInfo.symbolTable.irFactory as IdSignatureRetriever,
            stdlibIsMainModule = false,
            generateWat = generateWat,
            wasmDebug = true,
            outputFileNameBase = "index".takeIf { isMainModule },
        )

        val patchedMjs = compilerResult.jsUninstantiatedWrapper?.applyIf(isMainModule) {
            patchMjsToRelativePath(this, useNewExceptionProposal)
        }

        val patchedCompilerResult = WasmCompilerResult(
            wat = compilerResult.wat,
            jsUninstantiatedWrapper = patchedMjs,
            jsWrapper = compilerResult.jsWrapper,
            wasm = compilerResult.wasm,
            debugInformation = compilerResult.debugInformation,
            dts = compilerResult.dts,
            useDebuggerCustomFormatters = compilerResult.useDebuggerCustomFormatters,
            jsBuiltinsPolyfillsWrapper = compilerResult.jsBuiltinsPolyfillsWrapper,
            baseFileName = compilerResult.baseFileName,
        )

        return BinaryArtifacts.Wasm(
            patchedCompilerResult,
            patchedCompilerResult,
            null,
        )
    }
}
