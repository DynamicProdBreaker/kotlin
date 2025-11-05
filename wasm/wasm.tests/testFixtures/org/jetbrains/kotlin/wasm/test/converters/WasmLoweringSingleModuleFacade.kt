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
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.test.DebugMode
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.directives.WasmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.directives.WasmEnvironmentConfigurationDirectives.FORCE_DEBUG_FRIENDLY_COMPILATION
import org.jetbrains.kotlin.test.directives.WasmEnvironmentConfigurationDirectives.USE_NEW_EXCEPTION_HANDLING_PROPOSAL
import org.jetbrains.kotlin.test.model.*
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.compilerConfigurationProvider
import org.jetbrains.kotlin.test.services.configuration.WasmEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.defaultsProvider
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.util.PhaseType
import org.jetbrains.kotlin.utils.addToStdlib.applyIf
import org.jetbrains.kotlin.wasm.config.WasmConfigurationKeys
import org.jetbrains.kotlin.wasm.test.PrecompileSetup
import org.jetbrains.kotlin.wasm.test.handlers.getWasmTestOutputDirectory
import org.jetbrains.kotlin.wasm.test.precompiledKotlinTestOutputName
import org.jetbrains.kotlin.wasm.test.precompiledStdlibOutputName
import java.io.File

class WasmLoweringSingleModuleFacade(testServices: TestServices) :
    BackendFacade<IrBackendInput, BinaryArtifacts.Wasm>(testServices, BackendKinds.IrBackend, ArtifactKinds.Wasm) {

    private fun patchMjsToRelativePath(mjsContent: String, stdlibOutputDir: File, testOutputDir: File): String {
        val outputDirBase = testServices.getWasmTestOutputDirectory()
        fun replacePath(mjsText: String, originalPath: String, replacement: File): String {
            val relativeReplacement =
                replacement.relativeTo(outputDirBase).invariantSeparatorsPath
            val replacedMjsText = mjsText
                .replace(originalPath, relativeReplacement)
            check(replacedMjsText != mjsText) { "Replacement not applied" }
            return replacedMjsText
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

        val debugMode = DebugMode.fromSystemProperty("kotlin.wasm.debugMode")
        val generateWat = debugMode >= DebugMode.DEBUG || configuration.getBoolean(WasmConfigurationKeys.WASM_GENERATE_WAT)

        val generateDts = WasmEnvironmentConfigurationDirectives.CHECK_TYPESCRIPT_DECLARATIONS in testServices.moduleStructure.allDirectives
        val generateSourceMaps = WasmEnvironmentConfigurationDirectives.GENERATE_SOURCE_MAP in testServices.moduleStructure.allDirectives
        val useDebuggerCustomFormatters = debugMode >= DebugMode.DEBUG || configuration.getBoolean(JSConfigurationKeys.USE_DEBUGGER_CUSTOM_FORMATTERS)

        val (allModules, backendContext, typeScriptFragment) = compileToLoweredIr(
            moduleInfo,
            mainModule,
            configuration,
            performanceManager = performanceManager,
            exportedDeclarations = exportedDeclarations,
            propertyLazyInitialization = true,
            generateTypeScriptFragment = generateDts,
            disableCrossFileOptimisations = true,
        )

        val useNewExceptionProposal = USE_NEW_EXCEPTION_HANDLING_PROPOSAL in testServices.moduleStructure.allDirectives
        val debugFriendlyCompilation = FORCE_DEBUG_FRIENDLY_COMPILATION in testServices.moduleStructure.allDirectives

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
            useDebuggerCustomFormatters = useDebuggerCustomFormatters,
            typeScriptFragment = typeScriptFragment,
            generateSourceMaps = generateSourceMaps,
            generateDwarf = false,
        )

        val currentSetup = when {
            debugFriendlyCompilation -> PrecompileSetup.DEBUG_FRIENDLY
            useNewExceptionProposal -> PrecompileSetup.NEW_EXCEPTION_PROPOSAL
            else -> PrecompileSetup.REGULAR
        }

        val patchedMjs = compilerResult.jsUninstantiatedWrapper?.applyIf(isMainModule) {
            patchMjsToRelativePath(this, currentSetup.stdlibOutputDir, currentSetup.kotlinTestOutputDir)
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
