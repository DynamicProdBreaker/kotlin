/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.ir2wasm

import org.jetbrains.kotlin.backend.common.compilationException
import org.jetbrains.kotlin.backend.common.serialization.cityHash64
import org.jetbrains.kotlin.backend.wasm.MultimoduleCompileParameters
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.backend.wasm.ir2wasm.WasmCompiledModuleFragment.*
import org.jetbrains.kotlin.backend.wasm.utils.fitsLatin1
import org.jetbrains.kotlin.ir.backend.js.ic.IrICProgramFragment
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrExternalPackageFragment
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.wasm.ir.*
import org.jetbrains.kotlin.wasm.ir.WasmFunction
import org.jetbrains.kotlin.wasm.ir.source.location.SourceLocation
import kotlin.collections.mutableMapOf
import kotlin.collections.set

class BuiltinIdSignatures(
    val throwable: IdSignature?,
    val kotlinAny: IdSignature?,
    val tryGetAssociatedObject: IdSignature?,
    val jsToKotlinAnyAdapter: IdSignature?,
    val unitGetInstance: IdSignature?,
    val runRootSuites: IdSignature?,
    val createString: IdSignature?,
    val registerModuleDescriptor: IdSignature?,
)

class WasmCompiledFileFragment(
    val fragmentTag: String?,

    val definedFunctions: MutableMap<IdSignature, WasmFunction> = mutableMapOf(),
    val definedGlobalFields: MutableMap<IdSignature, WasmGlobal> = mutableMapOf(),
    val definedGlobalVTables: MutableMap<IdSignature, WasmGlobal> = mutableMapOf(),
    val definedGlobalClassITables: MutableMap<IdSignature, WasmGlobal> = mutableMapOf(),
    val definedRttiGlobal: MutableMap<IdSignature, WasmGlobal> = mutableMapOf(),
    val definedRttiSuperType: MutableMap<IdSignature, IdSignature?> = mutableMapOf(),

    val definedGcTypes: MutableMap<IdSignature, WasmTypeDeclaration> = mutableMapOf(),
    val definedVTableGcTypes: MutableMap<IdSignature, WasmTypeDeclaration> = mutableMapOf(),
    val definedFunctionTypes: MutableMap<IdSignature, WasmTypeDeclaration> = mutableMapOf(),


    val stringLiteralId: ReferencableElements<String, Int> = ReferencableElements(),
    val constantArrayDataSegmentId: ReferencableElements<Pair<List<Long>, WasmType>, Int> = ReferencableElements(),
    val jsFuns: MutableMap<IdSignature, JsCodeSnippet> = mutableMapOf(),
    val jsModuleImports: MutableMap<IdSignature, String> = mutableMapOf(),
    val jsBuiltinsPolyfills: MutableMap<String, String> = mutableMapOf(),
    val exports: MutableList<WasmExport<*>> = mutableListOf(),
    val mainFunctionWrappers: MutableList<IdSignature> = mutableListOf(),
    var testFunctionDeclarators: MutableList<IdSignature> = mutableListOf(),
    val equivalentFunctions: MutableList<Pair<String, IdSignature>> = mutableListOf(),
    val jsModuleAndQualifierReferences: MutableSet<JsModuleAndQualifierReference> = mutableSetOf(),
    val classAssociatedObjectsInstanceGetters: MutableList<ClassAssociatedObjects> = mutableListOf(),
    var builtinIdSignatures: BuiltinIdSignatures? = null,
    val objectInstanceFieldInitializers: MutableList<IdSignature> = mutableListOf(),
    val nonConstantFieldInitializers: MutableList<IdSignature> = mutableListOf(),
) : IrICProgramFragment()

enum class ExceptionTagType { WASM_TAG, JS_TAG, TRAP }

class WasmCompiledModuleFragment(private val wasmCompiledFileFragments: List<WasmCompiledFileFragment>) {
    // Used during linking
    private val serviceCodeLocation = SourceLocation.NoLocation("Generated service code")
    private val parameterlessNoReturnFunctionType = WasmFunctionType(emptyList(), emptyList())

    private val stringDataSectionIndex = WasmImmediate.DataIdx(0)
    private val stringAddressesAndLengthsIndex = WasmImmediate.DataIdx(1)

    private inline fun tryFindBuiltInFunction(select: (BuiltinIdSignatures) -> IdSignature?): IdSignature? {
        for (fragment in wasmCompiledFileFragments) {
            val builtinSignatures = fragment.builtinIdSignatures ?: continue
            return select(builtinSignatures) ?: continue
        }
        return null
    }

    private inline fun tryFindBuiltInType(select: (BuiltinIdSignatures) -> IdSignature?): IdSignature? {
        for (fragment in wasmCompiledFileFragments) {
            val builtinSignatures = fragment.builtinIdSignatures ?: continue
            return select(builtinSignatures) ?: continue
        }
        return null
    }

    class JsCodeSnippet(val importName: WasmSymbol<String>, val jsCode: String)

    open class ReferencableElements<Ir, Wasm : Any>(
        val unbound: MutableMap<Ir, WasmSymbol<Wasm>> = mutableMapOf()
    ) {
        fun reference(ir: Ir): WasmSymbol<Wasm> {
            val declaration = (ir as? IrSymbol)?.owner as? IrDeclarationWithName
            if (declaration != null) {
                val packageFragment = declaration.getPackageFragment()
                if (packageFragment is IrExternalPackageFragment) {
                    compilationException("Referencing declaration without package fragment", declaration)
                }
            }
            return unbound.getOrPut(ir) { WasmSymbol() }
        }
    }

    class ReferencableAndDefinable<Ir, Wasm : Any>(
        unbound: MutableMap<Ir, WasmSymbol<Wasm>> = mutableMapOf(),
        val defined: LinkedHashMap<Ir, Wasm> = LinkedHashMap(),
        val elements: MutableList<Wasm> = mutableListOf(),
        val wasmToIr: MutableMap<Wasm, Ir> = mutableMapOf()
    ) : ReferencableElements<Ir, Wasm>(unbound) {
        fun define(ir: Ir, wasm: Wasm) {
            if (ir in defined)
                compilationException("Trying to redefine element: IR: $ir Wasm: $wasm", type = null)

            elements += wasm
            defined[ir] = wasm
            wasmToIr[wasm] = ir
        }
    }

    private fun partitionDefinedAndImportedFunctions(allFunctions: MutableMap<IdSignature, WasmFunction>): Pair<MutableList<WasmFunction.Defined>, MutableList<WasmFunction.Imported>> {
        val definedFunctions = mutableListOf<WasmFunction.Defined>()
        val importedFunctions = mutableListOf<WasmFunction.Imported>()
        allFunctions.values.forEach { function ->
            when (function) {
                is WasmFunction.Defined -> definedFunctions.add(function)
                is WasmFunction.Imported -> importedFunctions.add(function)
            }
        }
        return definedFunctions to importedFunctions
    }

    private fun createAndExportServiceFunctions(
        definedDeclarations: DefinedDeclarations,
        stringEntities: StringLiteralWasmEntities,
        additionalTypes: MutableList<WasmTypeDeclaration>,
        stringPoolSize: Int,
        initializeUnit: Boolean,
        wasmElements: MutableList<WasmElement>,
        exports: MutableList<WasmExport<*>>,
        globals: MutableList<WasmGlobal>,
    ) {
        val definedFunctions = definedDeclarations.functions

        val (stringAddressesAndLengthsGlobal, wasmLongArrayDeclaration) = stringAddressesAndLengthsField(
            definedGcTypes = definedDeclarations.gcTypes,
            additionalTypes = additionalTypes
        )
        definedDeclarations.globalFields[Synthetics.Globals.addressesAndLengthsGlobal.value] = stringAddressesAndLengthsGlobal
        globals.add(stringAddressesAndLengthsGlobal)

        val fieldInitializerFunction =
            createFieldInitializerFunction(stringPoolSize, wasmLongArrayDeclaration)
        definedFunctions[Synthetics.Functions.fieldInitializerFunction.value] = fieldInitializerFunction

        val associatedObjectGetterAndWrapper = createAssociatedObjectGetterFunctionAndWrapper(definedDeclarations, wasmElements, additionalTypes)
        if (associatedObjectGetterAndWrapper != null) {
            definedFunctions[Synthetics.Functions.tryGetAssociatedObjectAndWrapper.value] = associatedObjectGetterAndWrapper.first
            additionalTypes.add(associatedObjectGetterAndWrapper.second)
        }

        val masterInitFunction = createAndExportMasterInitFunction(
            allFunctions = definedFunctions,
            tryGetAssociatedObjectAndWrapper = associatedObjectGetterAndWrapper,
            initializeUnit = initializeUnit
        )

        exports.add(WasmExport.Function("_initialize", masterInitFunction))
        definedFunctions[Synthetics.Functions.masterInitFunction.value] = masterInitFunction

        val stringPoolField = createStringPoolField(stringPoolSize)
        definedDeclarations.globalFields[Synthetics.Globals.stringPoolGlobal.value] = stringPoolField
        globals.add(stringPoolField)

        val stringLiteralFunctionLatin1 =
            createStringLiteralFunction(
                definedGcTypes = definedDeclarations.gcTypes,
                stringEntities = stringEntities,
                additionalTypes = additionalTypes,
                wasmElements = wasmElements,
                isLatin1 = true,
            )
        definedFunctions[Synthetics.Functions.createStringLiteralLatin1.value] = stringLiteralFunctionLatin1

        val stringLiteralFunctionUtf16 =
            createStringLiteralFunction(
                definedGcTypes = definedDeclarations.gcTypes,
                stringEntities = stringEntities,
                additionalTypes = additionalTypes,
                wasmElements = wasmElements,
                isLatin1 = false,
            )
        definedFunctions[Synthetics.Functions.createStringLiteralUtf16.value] = stringLiteralFunctionUtf16

        val startUnitTestsFunction = createStartUnitTestsFunction(definedFunctions)
        if (startUnitTestsFunction != null) {
            exports.add(WasmExport.Function("startUnitTests", startUnitTestsFunction))
            definedFunctions[Synthetics.Functions.startUnitTestsFunction.value] = startUnitTestsFunction
        }
    }

    class StringLiteralWasmEntities(
        val createStringSignature: IdSignature,
        val kotlinStringType: WasmType,
        val wasmCharArrayType: WasmType,
        val wasmCharArrayDeclaration: IdSignature,
        val stringLiteralFunctionType: WasmFunctionType,
    )

    fun getStringLiteralWasmEntities(
        definedDeclarations: DefinedDeclarations,
        canonicalFunctionTypes: Map<WasmFunctionType, WasmFunctionType>,
        syntheticTypes: MutableList<WasmTypeDeclaration>,
        additionalTypes: MutableList<WasmTypeDeclaration>
    ): StringLiteralWasmEntities {
        val createStringSignature = tryFindBuiltInFunction { it.createString }
            ?: compilationException("kotlin.createString is not file in fragments", null)
        val createStringFunction = definedDeclarations.functions[createStringSignature]
            ?: compilationException("kotlin.createString is not file in fragments", null)

        val createStringFunctionType = definedDeclarations.functionTypes.getValue(createStringFunction.type.value)
        val kotlinStringType = createStringFunctionType.resultTypes[0]
        val wasmCharArrayType = createStringFunctionType.parameterTypes[0]
        val wasmCharArrayDeclaration = (wasmCharArrayType.getHeapType() as WasmHeapType.Type).type
        val wasmStringArrayDeclaration =
            WasmArrayDeclaration("string_array", WasmStructFieldDeclaration("string", kotlinStringType, true))
        additionalTypes.add(wasmStringArrayDeclaration)

        val newStringLiteralFunctionType = WasmFunctionType(listOf(WasmI32), listOf(kotlinStringType))
        val stringLiteralFunctionType = canonicalFunctionTypes[newStringLiteralFunctionType] ?: newStringLiteralFunctionType
        if (stringLiteralFunctionType === newStringLiteralFunctionType) {
            syntheticTypes.add(newStringLiteralFunctionType)
        }
        definedDeclarations.functionTypes[Synthetics.FunctionGcTypes.stringLiteralFunctionType.value] = stringLiteralFunctionType

        return StringLiteralWasmEntities(
            createStringSignature = createStringSignature,
            kotlinStringType = kotlinStringType,
            wasmCharArrayType = wasmCharArrayType,
            wasmCharArrayDeclaration = wasmCharArrayDeclaration,
            stringLiteralFunctionType = stringLiteralFunctionType,
        )
    }

    fun linkWasmCompiledFragments(
        multimoduleParameters: MultimoduleCompileParameters?,
        exceptionTagType: ExceptionTagType
    ): WasmModule {
        // TODO: Implement optimal ir linkage KT-71040
        val definedDeclarations = bindUnboundSymbols()

        val canonicalFunctionTypes = bindUnboundFunctionTypes()

        val data = mutableListOf<WasmData>()
        val stringPoolSize = bindStringPoolSymbolsAndGetSize(data)
        bindConstantArrayDataSegmentIds(data)

        val exports = mutableListOf<WasmExport<*>>()
        wasmCompiledFileFragments.flatMapTo(exports) { it.exports }

        val memories = createAndExportMemory(exports, multimoduleParameters?.stdlibModuleNameForImport)
        val (importedMemories, definedMemories) = memories.partition { it.importPair != null }

        val additionalTypes = mutableListOf<WasmTypeDeclaration>()
        additionalTypes.add(parameterlessNoReturnFunctionType)

        val syntheticTypes = mutableListOf<WasmTypeDeclaration>()
        val stringEntities = getStringLiteralWasmEntities(definedDeclarations, canonicalFunctionTypes, syntheticTypes, additionalTypes)

        createAndBindSpecialITableTypes(definedDeclarations.gcTypes, syntheticTypes)
        createAndBindRttiTypeDeclaration(definedDeclarations.gcTypes, syntheticTypes)

        val globals = getGlobals(definedDeclarations)

        val elements = mutableListOf<WasmElement>()
        createAndExportServiceFunctions(
            definedDeclarations = definedDeclarations,
            stringEntities = stringEntities,
            additionalTypes = additionalTypes,
            stringPoolSize = stringPoolSize,
            initializeUnit = multimoduleParameters?.initializeUnit ?: true,
            wasmElements = elements,
            exports = exports,
            globals = globals
        )

        val tags = getTags(exceptionTagType)
        require(tags.size <= 1) { "Having more than 1 tag is not supported" }

        val (importedTags, definedTags) = tags.partition { it.importPair != null }
        tags.forEach { additionalTypes.add(it.type) }

        val (importedGlobals, definedGlobals) = globals.partition { it.importPair != null }

        val (definedFunctions, importedFunctions) = partitionDefinedAndImportedFunctions(definedDeclarations.functions)

        val importsInOrder = mutableListOf<WasmNamedModuleField>()
        importsInOrder.addAll(importedFunctions)
        importsInOrder.addAll(importedTags)
        importsInOrder.addAll(importedGlobals)
        importsInOrder.addAll(importedMemories)

        val recursiveTypeGroups = getTypes(definedDeclarations.gcTypes, syntheticTypes, canonicalFunctionTypes, additionalTypes)

        return WasmModule(
            definedDeclarations = definedDeclarations,
            recGroups = recursiveTypeGroups,
            importsInOrder = importsInOrder,
            importedFunctions = importedFunctions,
            importedMemories = importedMemories,
            definedFunctions = definedFunctions,
            importedTags = importedTags,
            tables = emptyList(),
            memories = definedMemories,
            globals = definedGlobals,
            importedGlobals = importedGlobals,
            exports = exports,
            startFunction = null,  // Module is initialized via export call
            elements = elements,
            data = data,
            dataCount = true,
            tags = definedTags
        ).apply { calculateIds() }
    }

    private fun createAndBindSpecialITableTypes(
        definedGcTypes: MutableMap<IdSignature, WasmTypeDeclaration>,
        syntheticTypes: MutableList<WasmTypeDeclaration>
    ): MutableList<WasmTypeDeclaration> {
        val wasmAnyArrayType = WasmArrayDeclaration(
            name = "AnyArray",
            field = WasmStructFieldDeclaration("", WasmRefNullType(WasmHeapType.Simple.Any), false)
        )
        definedGcTypes[Synthetics.GcTypes.wasmAnyArrayType.value] = wasmAnyArrayType
        syntheticTypes.add(wasmAnyArrayType)

        val specialSlotITableTypeSlots = mutableListOf<WasmStructFieldDeclaration>()
        val wasmAnyRefStructField = WasmStructFieldDeclaration("", WasmAnyRef, false)
        repeat(WasmBackendContext.SPECIAL_INTERFACE_TABLE_SIZE) {
            specialSlotITableTypeSlots.add(wasmAnyRefStructField)
        }
        specialSlotITableTypeSlots.add(
            WasmStructFieldDeclaration(
                name = "",
                type = WasmRefNullType(WasmHeapType.Type(Synthetics.GcTypes.wasmAnyArrayType.value)),
                isMutable = false
            )
        )
        val specialSlotITableType = WasmStructDeclaration(
            name = "SpecialITable",
            fields = specialSlotITableTypeSlots,
            superType = null,
            isFinal = true
        )
        definedGcTypes[Synthetics.GcTypes.specialSlotITableType.value] = specialSlotITableType
        syntheticTypes.add(specialSlotITableType)

        return syntheticTypes
    }

    private fun getTags(exceptionTagType: ExceptionTagType): List<WasmTag> {
        val exceptionTag = when (exceptionTagType) {
            ExceptionTagType.TRAP -> null
            ExceptionTagType.JS_TAG -> {
                val jsExceptionTagFuncType = WasmFunctionType(
                    parameterTypes = listOf(WasmExternRef),
                    resultTypes = emptyList()
                )
                WasmTag(jsExceptionTagFuncType, WasmImportDescriptor("intrinsics", WasmSymbol("tag")))
            }
            ExceptionTagType.WASM_TAG -> {
                val throwableDeclaration = tryFindBuiltInType { it.throwable }
                    ?: compilationException("kotlin.Throwable is not found in fragments", null)

                val tagFuncType = WasmRefNullType(WasmHeapType.Type(throwableDeclaration))

                val throwableTagFuncType = WasmFunctionType(
                    parameterTypes = listOf(tagFuncType),
                    resultTypes = emptyList()
                )

                WasmTag(throwableTagFuncType)
            }
        }
        return listOfNotNull(exceptionTag)
    }

    private fun getTypes(
        definedGcTypes: MutableMap<IdSignature, WasmTypeDeclaration>,
        additionalRecGroupTypes: List<WasmTypeDeclaration>,
        canonicalFunctionTypes: Map<WasmFunctionType, WasmFunctionType>,
        additionalTypes: List<WasmTypeDeclaration>,
    ): List<RecursiveTypeGroup> {
        val vTableGcTypes = wasmCompiledFileFragments.flatMap { it.vTableGcTypes.elements }

        val recGroupTypes = buildList {
            addAll(additionalRecGroupTypes)
            addAll(definedGcTypes.values)
            addAll(vTableGcTypes)
            addAll(canonicalFunctionTypes.values)
        }

        val recursiveGroups = createRecursiveTypeGroups(recGroupTypes)

        recursiveGroups.forEach { group ->
            if (group.singleOrNull() is WasmArrayDeclaration) {
                return@forEach
            }

            val needMixIn = group.any { it in definedGcTypes.values }
            val needStableSort = needMixIn || group.any { it in vTableGcTypes }

            canonicalSort(group, needStableSort)

            if (needMixIn) {
                val firstGroupGcTypeSignature = group.firstNotNullOfOrNull {
                    definedGcTypes.entries.firstOrNull { it.value == it }
                } ?: compilationException("The group should have gcType to have a mixin", null)

                val mixin64BitIndex = firstGroupGcTypeSignature.toString().cityHash64().toULong()

                val mixIn = WasmStructDeclaration(
                    name = "mixin_type",
                    fields = encodeIndex(mixin64BitIndex),
                    superType = null,
                    isFinal = true
                )
                group.add(mixIn)
            }
        }

        additionalTypes.forEach { recursiveGroups.add(mutableListOf(it)) }
        return recursiveGroups
    }

    private fun createAndBindRttiTypeDeclaration(
        definedGcTypes: MutableMap<IdSignature, WasmTypeDeclaration>,
        syntheticTypes: MutableList<WasmTypeDeclaration>
    ) {
        val wasmLongArray = WasmArrayDeclaration("LongArray", WasmStructFieldDeclaration("Long", WasmI64, false))
        syntheticTypes.add(wasmLongArray)

        val stringLiteralFunctionRef = WasmRefNullType(WasmHeapType.Type(Synthetics.stringLiteralFunctionType))

        val rttiTypeDeclaration = WasmStructDeclaration(
            name = "RTTI",
            fields = listOf(
                WasmStructFieldDeclaration("implementedIFaceIds", WasmRefNullType(Synthetics.HeapTypes.wasmLongArray), false),
                WasmStructFieldDeclaration("superClassRtti", WasmRefNullType(Synthetics.HeapTypes.rttiType), false),
                WasmStructFieldDeclaration("packageNamePoolId", WasmI32, false),
                WasmStructFieldDeclaration("simpleNamePoolId", WasmI32, false),
                WasmStructFieldDeclaration("klassId", WasmI64, false),
                WasmStructFieldDeclaration("typeInfoFlag", WasmI32, false),
                WasmStructFieldDeclaration("qualifierStringLoader", stringLiteralFunctionRef, false),
                WasmStructFieldDeclaration("simpleNameStringLoader", stringLiteralFunctionRef, false),
            ),
            superType = null,
            isFinal = true
        )
        definedGcTypes[Synthetics.GcTypes.rttiType.value] = rttiTypeDeclaration
        syntheticTypes.add(rttiTypeDeclaration)
    }

    private fun getGlobals(definedDeclarations: DefinedDeclarations) = mutableListOf<WasmGlobal>().apply {
        addAll(definedDeclarations.globalFields.values)
        addAll(definedDeclarations.globalVTables.values)
        addAll(definedDeclarations.globalClassITables.values)

        val rttiGlobals = mutableMapOf<IdSignature, WasmGlobal>()
        val rttiSuperTypes = mutableMapOf<IdSignature, IdSignature?>()
        wasmCompiledFileFragments.forEach { fragment ->
            rttiGlobals.putAll(fragment.definedRttiGlobal)
            rttiSuperTypes.putAll(fragment.definedRttiSuperType)
        }

        fun wasmRttiGlobalOrderKey(superType: IdSignature?): Int =
            superType?.let { wasmRttiGlobalOrderKey(rttiSuperTypes[it]) + 1 } ?: 0

        rttiGlobals.keys.sortedBy(::wasmRttiGlobalOrderKey).mapTo(this) { rttiGlobals[it]!! }
    }

    private fun createAndExportMemory(exports: MutableList<WasmExport<*>>, stdlibModuleNameForImport: String?): List<WasmMemory> {
        val memorySizeInPages = 0
        val importPair = stdlibModuleNameForImport?.let { WasmImportDescriptor(it, WasmSymbol("memory")) }
        val memory = WasmMemory(WasmLimits(memorySizeInPages.toUInt(), null/* "unlimited" */), importPair)

        // Need to export the memory in order to pass complex objects to the host language.
        // Export name "memory" is a WASI ABI convention.
        val exportMemory = WasmExport.Memory("memory", memory)
        exports.add(exportMemory)
        return listOf(memory)
    }

    private fun createAndExportMasterInitFunction(
        allFunctions: MutableMap<IdSignature, WasmFunction>,
        tryGetAssociatedObjectAndWrapper: Pair<WasmFunction.Defined, WasmStructDeclaration>?,
        initializeUnit: Boolean,
    ): WasmFunction.Defined {
        val masterInitFunction = WasmFunction.Defined("_initialize", WasmSymbol(parameterlessNoReturnFunctionType))
        with(WasmExpressionBuilder(masterInitFunction.instructions)) {
            if (initializeUnit) {
                val unitGetInstance = tryFindBuiltInFunction { it.unitGetInstance }
                    ?: compilationException("kotlin.Unit_getInstance is not file in fragments", null)
                buildCall(unitGetInstance, serviceCodeLocation)
            }

            buildCall(Synthetics.Functions.fieldInitializerFunction, serviceCodeLocation)

            if (tryGetAssociatedObjectAndWrapper != null) {
                // we do not register descriptor while no need in it
                val registerModuleDescriptor = tryFindBuiltInFunction { it.registerModuleDescriptor }
                    ?: compilationException("kotlin.registerModuleDescriptor is not file in fragments", null)

                buildInstr(WasmOp.REF_FUNC, serviceCodeLocation, Synthetics.Functions.tryGetAssociatedObjectAndWrapper)
                buildInstr(WasmOp.STRUCT_NEW, serviceCodeLocation, WasmImmediate.GcType(WasmSymbol(tryGetAssociatedObjectAndWrapper.second)))
                buildInstr(WasmOp.CALL, serviceCodeLocation, WasmImmediate.FuncIdx(registerModuleDescriptor))
            }

            wasmCompiledFileFragments.forEach { fragment ->
                fragment.mainFunctionWrappers.forEach { signature ->
                    buildCall(signature, serviceCodeLocation)
                }
            }
            buildInstr(WasmOp.RETURN, serviceCodeLocation)
        }
        return masterInitFunction
    }

    private fun createAssociatedObjectGetterFunctionAndWrapper(
        definedDeclarations: DefinedDeclarations,
        wasmElements: MutableList<WasmElement>,
        additionalTypes: MutableList<WasmTypeDeclaration>
    ): Pair<WasmFunction.Defined, WasmStructDeclaration>? {
        // If AO accessor removed by DCE - we do not need it then
        if (tryFindBuiltInFunction { it.tryGetAssociatedObject } == null) return null

        val kotlinAny = tryFindBuiltInType { it.kotlinAny }
            ?: compilationException("kotlin.Any is not found in fragments", null)

        val nullableAnyWasmType = WasmRefNullType(WasmHeapType.Type(kotlinAny))
        val associatedObjectGetterType = WasmFunctionType(listOf(WasmI64, WasmI64), listOf(nullableAnyWasmType))
        additionalTypes.add(associatedObjectGetterType)

        val associatedObjectGetter = WasmFunction.Defined("_associatedObjectGetter", WasmSymbol(associatedObjectGetterType))
        // Make this function possible to func.ref
        wasmElements.add(
            WasmElement(
                type = WasmFuncRef,
                values = listOf(WasmTable.Value.Function(WasmSymbol(associatedObjectGetter))),
                mode = WasmElement.Mode.Declarative
            )
        )

        val jsToKotlinAnyAdapter by lazy {
            tryFindBuiltInFunction { it.jsToKotlinAnyAdapter }
                ?: compilationException("kotlin.jsToKotlinAnyAdapter is not found in fragments", null)
        }

        associatedObjectGetter.instructions.clear()
        with(WasmExpressionBuilder(associatedObjectGetter.instructions)) {
            wasmCompiledFileFragments.forEach { fragment ->
                for ((klassId, associatedObjectsInstanceGetters) in fragment.classAssociatedObjectsInstanceGetters) {
                    buildGetLocal(WasmLocal(0, "classId", WasmI64, true), serviceCodeLocation)
                    buildConstI64(klassId, serviceCodeLocation)
                    buildInstr(WasmOp.I64_EQ, serviceCodeLocation)
                    buildIf("Class matches")
                    associatedObjectsInstanceGetters.forEach { (keyId, getter, isExternal) ->
                        if (definedDeclarations.functions.containsKey(getter)) { //Could be deleted with DCE
                            buildGetLocal(WasmLocal(1, "keyId", WasmI64, true), serviceCodeLocation)
                            buildConstI64(keyId, serviceCodeLocation)
                            buildInstr(WasmOp.I64_EQ, serviceCodeLocation)
                            buildIf("Object matches")
                            buildCall(getter, serviceCodeLocation)
                            if (isExternal) {
                                buildCall(jsToKotlinAnyAdapter, serviceCodeLocation)
                            }
                            buildInstr(WasmOp.RETURN, serviceCodeLocation)
                            buildEnd()
                        }
                    }
                    buildEnd()
                }
            }
            buildRefNull(WasmHeapType.Simple.None, serviceCodeLocation)
            buildInstr(WasmOp.RETURN, serviceCodeLocation)
        }

        val associatedObjectGetterTypeRef =
            WasmRefType(Synthetics.HeapTypes.associatedObjectGetterType)

        val associatedObjectGetterWrapper = WasmStructDeclaration(
            name = "AssociatedObjectGetterWrapper",
            fields = listOf(WasmStructFieldDeclaration("getter", associatedObjectGetterTypeRef, false)),
            superType = null,
            isFinal = true
        )
        definedDeclarations[Synthetics.GcTypes.associatedObjectGetterWrapper.value] = associatedObjectGetterWrapper

        return associatedObjectGetter to associatedObjectGetterWrapper
    }

    private fun createStartUnitTestsFunction(allFunctions: MutableMap<IdSignature, WasmFunction>): WasmFunction.Defined? {
        val runRootSuites = tryFindBuiltInFunction { it.runRootSuites } ?: return null
        if (!allFunctions.containsKey(runRootSuites)) return null

        val startUnitTestsFunction = WasmFunction.Defined("startUnitTests", WasmSymbol(parameterlessNoReturnFunctionType))
        with(WasmExpressionBuilder(startUnitTestsFunction.instructions)) {
            wasmCompiledFileFragments.forEach { fragment ->
                fragment.testFunctionDeclarators.forEach { declarator ->
                    buildCall(declarator, serviceCodeLocation)
                }
            }
            buildCall(runRootSuites, serviceCodeLocation)
        }
        return startUnitTestsFunction
    }

    private fun createFieldInitializerFunction(
        stringPoolSize: Int,
        wasmLongArrayDeclaration: WasmArrayDeclaration
    ): WasmFunction.Defined {
        val fieldInitializerFunction = WasmFunction.Defined("_fieldInitialize", WasmSymbol(parameterlessNoReturnFunctionType))
        with(WasmExpressionBuilder(fieldInitializerFunction.instructions)) {
            buildConstI32(0, serviceCodeLocation)
            buildConstI32(stringPoolSize, serviceCodeLocation)
            buildInstr(
                WasmOp.ARRAY_NEW_DATA,
                serviceCodeLocation,
                Synthetics.GcTypes.wasmLongArrayDeclaration,
                stringAddressesAndLengthsIndex,
            )
            buildSetGlobal(Synthetics.Globals.addressesAndLengthsGlobal, serviceCodeLocation)

            wasmCompiledFileFragments.forEach { fragment ->
                fragment.objectInstanceFieldInitializers.forEach { objectInitializer ->
                    buildCall(objectInitializer, serviceCodeLocation)
                }
            }

            wasmCompiledFileFragments.forEach { fragment ->
                fragment.nonConstantFieldInitializers.forEach { nonConstantInitializer ->
                    buildCall(nonConstantInitializer, serviceCodeLocation)
                }
            }
        }
        return fieldInitializerFunction
    }

    private fun stringAddressesAndLengthsField(
        definedGcTypes: MutableMap<IdSignature, WasmTypeDeclaration>,
        additionalTypes: MutableList<WasmTypeDeclaration>
    ): Pair<WasmGlobal, WasmArrayDeclaration> {
        val wasmLongArrayDeclaration =
            WasmArrayDeclaration("long_array", WasmStructFieldDeclaration("long", WasmI64, false))
        definedGcTypes[Synthetics.GcTypes.wasmLongArrayDeclaration.value] = wasmLongArrayDeclaration
        additionalTypes.add(wasmLongArrayDeclaration)

        val stringAddressesAndLengthsInitializer = listOf(
            WasmInstr1(
                operator = WasmOp.REF_NULL,
                immediate1 = WasmImmediate.HeapType(WasmRefNullrefType)
            ),
        )

        val refAddressesAndLengthsType =
            WasmRefNullType(Synthetics.HeapTypes.wasmLongArrayDeclaration)

        val global = WasmGlobal("_addressesAndLengths", refAddressesAndLengthsType, true, stringAddressesAndLengthsInitializer)
        return global to wasmLongArrayDeclaration
    }

    private fun createStringPoolField(stringPoolSize: Int): WasmGlobal {
        val stringCacheFieldInitializer = listOf(
            WasmInstr1(
                operator = WasmOp.I32_CONST,
                immediate1 = WasmImmediate.ConstI32(stringPoolSize),
            ),
            WasmInstr1(
                operator = WasmOp.ARRAY_NEW_DEFAULT,
                immediate1 = Synthetics.GcTypes.wasmStringArrayType
            ),
        )

        val refToArrayOfNullableStringsType =
            WasmRefType(Synthetics.HeapTypes.wasmStringArrayType)

        return WasmGlobal("_stringPool", refToArrayOfNullableStringsType, false, stringCacheFieldInitializer)
    }

    private fun createStringLiteralFunction(
        definedGcTypes: MutableMap<IdSignature, WasmTypeDeclaration>,
        stringEntities: StringLiteralWasmEntities,
        additionalTypes: MutableList<WasmTypeDeclaration>,
        wasmElements: MutableList<WasmElement>,
        isLatin1: Boolean,
    ): WasmFunction.Defined {
        val byteArray = WasmArrayDeclaration("byteArray", WasmStructFieldDeclaration("byte", WasmI8, false))
        definedGcTypes[Synthetics.GcTypes.byteArray.value] = byteArray
        additionalTypes.add(byteArray)

        val poolIdLocal = WasmLocal(0, "poolId", WasmI32, true)

        val startAddress = WasmLocal(1, "startAddress", WasmI32, false)
        val length = WasmLocal(2, "length", WasmI32, false)
        val addressAndLength = WasmLocal(3, "addressAndLength", WasmI64, false)
        val temporary = WasmLocal(4, "temporary", stringEntities.kotlinStringType, false)

        val stringLiteralFunction = WasmFunction.Defined(
            name = "_stringLiteral${if (isLatin1) "Latin1" else "Utf16"}",
            type = WasmSymbol(stringEntities.stringLiteralFunctionType),
            locals = mutableListOf(startAddress, length, addressAndLength, temporary)
        )
        with(WasmExpressionBuilder(stringLiteralFunction.instructions)) {
            buildBlock("cache_check", stringEntities.kotlinStringType) { blockResult ->
                buildGetGlobal(Synthetics.Globals.stringPoolGlobal, serviceCodeLocation)
                buildGetLocal(poolIdLocal, serviceCodeLocation)
                buildInstr(
                    WasmOp.ARRAY_GET,
                    serviceCodeLocation,
                    Synthetics.GcTypes.wasmStringArrayType
                )
                buildBrInstr(WasmOp.BR_ON_NON_NULL, blockResult, serviceCodeLocation)

                // cache miss
                buildGetGlobal(Synthetics.Globals.addressesAndLengthsGlobal, serviceCodeLocation)
                buildGetLocal(poolIdLocal, serviceCodeLocation)
                buildInstr(
                    op = WasmOp.ARRAY_GET,
                    location = serviceCodeLocation,
                    Synthetics.GcTypes.wasmLongArrayDeclaration
                )
                buildSetLocal(addressAndLength, serviceCodeLocation)

                //Get length
                buildGetLocal(addressAndLength, serviceCodeLocation)
                buildConstI64(32L, serviceCodeLocation)
                buildInstr(
                    op = WasmOp.I64_SHR_S,
                    location = serviceCodeLocation,
                )
                buildInstr(
                    op = WasmOp.I32_WRAP_I64,
                    location = serviceCodeLocation,
                )
                buildSetLocal(length, serviceCodeLocation)

                //Get startAddress
                buildGetLocal(addressAndLength, serviceCodeLocation)
                buildInstr(
                    op = WasmOp.I32_WRAP_I64,
                    location = serviceCodeLocation,
                )
                buildSetLocal(startAddress, serviceCodeLocation)

                // create new string
                buildGetLocal(startAddress, serviceCodeLocation)
                buildGetLocal(length, serviceCodeLocation)

                if (!isLatin1) {
                    buildInstr(
                        op = WasmOp.ARRAY_NEW_DATA,
                        location = serviceCodeLocation,
                        WasmImmediate.GcTypeIdx(stringEntities.wasmCharArrayDeclaration), stringDataSectionIndex
                    )
                } else {
                    val iterator = WasmLocal(5, "intIterator", WasmI32, false)
                    stringLiteralFunction.locals.add(iterator)
                    val wasmByteArray = WasmLocal(6, "byteArray", WasmRefType(Synthetics.HeapTypes.byteArray), false)
                    stringLiteralFunction.locals.add(wasmByteArray)
                    val wasmCharArray = WasmLocal(7, "charArray", stringEntities.wasmCharArrayType, false)
                    stringLiteralFunction.locals.add(wasmCharArray)

                    buildInstr(
                        op = WasmOp.ARRAY_NEW_DATA,
                        location = serviceCodeLocation,
                        Synthetics.GcTypes.byteArray, stringDataSectionIndex
                    )
                    buildSetLocal(wasmByteArray, serviceCodeLocation)

                    buildGetLocal(length, serviceCodeLocation)
                    buildInstr(
                        op = WasmOp.ARRAY_NEW_DEFAULT,
                        location = serviceCodeLocation,
                        WasmImmediate.GcTypeIdx(stringEntities.wasmCharArrayDeclaration)
                    )
                    buildSetLocal(wasmCharArray, serviceCodeLocation)

                    buildBlock("loop_body") { loopExit ->
                        buildLoop("copy_loop") { loop ->
                            buildGetLocal(iterator, serviceCodeLocation)
                            buildGetLocal(length, serviceCodeLocation)
                            buildInstr(WasmOp.I32_EQ, serviceCodeLocation)
                            buildBrIf(loopExit, serviceCodeLocation)

                            // char array set
                            buildGetLocal(wasmCharArray, serviceCodeLocation)
                            buildGetLocal(iterator, serviceCodeLocation)

                            // byte array get
                            buildGetLocal(wasmByteArray, serviceCodeLocation)
                            buildGetLocal(iterator, serviceCodeLocation)
                            buildInstr(WasmOp.ARRAY_GET_U, serviceCodeLocation, WasmImmediate.GcTypeIdx(Synthetics.byteArray))

                            buildInstr(WasmOp.ARRAY_SET, serviceCodeLocation, WasmImmediate.GcTypeIdx(stringEntities.wasmCharArrayDeclaration))

                            buildGetLocal(iterator, serviceCodeLocation)
                            buildConstI32(1, serviceCodeLocation)
                            buildInstr(WasmOp.I32_ADD, serviceCodeLocation)
                            buildSetLocal(iterator, serviceCodeLocation)
                            buildBr(loop, serviceCodeLocation)
                        }
                    }
                    buildGetLocal(wasmCharArray, serviceCodeLocation)
                }

                buildCall(stringEntities.createStringSignature, serviceCodeLocation)
                buildSetLocal(temporary, serviceCodeLocation)

                //remember and return string
                buildGetGlobal(Synthetics.Globals.stringPoolGlobal, serviceCodeLocation)
                buildGetLocal(poolIdLocal, serviceCodeLocation)
                buildGetLocal(temporary, serviceCodeLocation)
                buildInstr(
                    WasmOp.ARRAY_SET,
                    serviceCodeLocation,
                    Synthetics.GcTypes.wasmStringArrayType
                )
                buildGetLocal(temporary, serviceCodeLocation)
            }
            buildInstr(WasmOp.RETURN, serviceCodeLocation)
        }

        // Make this function possible to func.ref
        wasmElements.add(
            WasmElement(
                type = WasmFuncRef,
                values = listOf(WasmTable.Value.Function(WasmSymbol(stringLiteralFunction))),
                mode = WasmElement.Mode.Declarative
            )
        )

        return stringLiteralFunction
    }

    private fun bindUnboundSymbols(): DefinedDeclarations {

        val singleFragment = wasmCompiledFileFragments.singleOrNull()
        val definedDeclarations = if (singleFragment != null) {
            DefinedDeclarations(
                functions = singleFragment.definedFunctions,
                globalFields = singleFragment.definedGlobalFields,
                globalVTables = singleFragment.definedGlobalVTables,
                globalClassITables = singleFragment.definedGlobalClassITables,
                globalRTTI = singleFragment.definedRttiGlobal,
                gcTypes = singleFragment.definedGcTypes,
            )
        } else {
            DefinedDeclarations().also { definedDeclarations ->
                wasmCompiledFileFragments.forEach { fragment ->
                    definedDeclarations.functions.putAll(fragment.definedFunctions)
                    definedDeclarations.globalFields.putAll(fragment.definedGlobalFields)
                    definedDeclarations.globalVTables.putAll(fragment.definedGlobalVTables)
                    definedDeclarations.globalClassITables.putAll(fragment.definedGlobalClassITables)
                    definedDeclarations.globalRTTI.putAll(fragment.definedRttiGlobal)
                    definedDeclarations.gcTypes.putAll(fragment.definedGcTypes)
                }
            }
        }

        rebindEquivalentFunctions(definedDeclarations.functions)
        bindUniqueJsFunNames()

        return definedDeclarations
    }

    private fun <IrSymbolType, WasmDeclarationType : Any, WasmSymbolType : WasmSymbol<WasmDeclarationType>> bindFileFragments(
        fragments: List<WasmCompiledFileFragment>,
        unboundSelector: (WasmCompiledFileFragment) -> Map<IrSymbolType, WasmSymbolType>,
        definedSelector: (WasmCompiledFileFragment) -> Map<IrSymbolType, WasmDeclarationType>,
    ) {
        val allDefined = mutableMapOf<IrSymbolType, WasmDeclarationType>()
        fragments.forEach { fragment ->
            definedSelector(fragment).forEach { defined ->
                check(!allDefined.containsKey(defined.key)) {
                    "Redeclaration of symbol ${defined.key}"
                }
                allDefined[defined.key] = defined.value
            }
        }
        for (fragment in fragments) {
            val unbound = unboundSelector(fragment)
            bind(unbound, allDefined)
        }
    }

    private fun bindUnboundFunctionTypes(): Map<WasmFunctionType, WasmFunctionType> {
        // Associate function types to a single canonical function type
        val canonicalFunctionTypes = LinkedHashMap<WasmFunctionType, WasmFunctionType>()
        wasmCompiledFileFragments.forEach { fragment ->
            fragment.functionTypes.elements.associateWithTo(canonicalFunctionTypes) { it }
        }
        // Rebind symbol to canonical
        wasmCompiledFileFragments.forEach { fragment ->
            fragment.functionTypes.unbound.forEach { (_, wasmSymbol) ->
                wasmSymbol.bind(canonicalFunctionTypes.getValue(wasmSymbol.owner))
            }
        }
        return canonicalFunctionTypes
    }

    private fun bindStringPoolSymbolsAndGetSize(data: MutableList<WasmData>): Int {
        val stringDataSectionBytes = mutableListOf<Byte>()
        var stringDataSectionStart = 0
        val visitedStrings = mutableMapOf<String, Int>()
        val addressesAndLengths = mutableListOf<Long>()
        wasmCompiledFileFragments.forEach { fragment ->
            for ((string, literalIdSymbol) in fragment.stringLiteralId.unbound) {
                val visitedStringId = visitedStrings[string]
                val stringId: Int
                if (visitedStringId == null) {
                    stringId = visitedStrings.size
                    visitedStrings[string] = stringId

                    addressesAndLengths.add(stringDataSectionStart.toLong() or (string.length.toLong() shl 32))
                    val constData = ConstantDataCharArray(string.toCharArray(), string.fitsLatin1)
                    stringDataSectionBytes += constData.toBytes().toList()
                    stringDataSectionStart += constData.sizeInBytes
                } else {
                    stringId = visitedStringId
                }
                literalIdSymbol.bind(stringId)
            }
        }

        data.add(WasmData(WasmDataMode.Passive, stringDataSectionBytes.toByteArray()))
        val constDataAddressesAndLengths = ConstantDataIntegerArray(addressesAndLengths, LONG_SIZE_BYTES)
        data.add(WasmData(WasmDataMode.Passive, constDataAddressesAndLengths.toBytes()))

        return visitedStrings.size
    }

    private fun bindConstantArrayDataSegmentIds(data: MutableList<WasmData>) {
        wasmCompiledFileFragments.forEach { fragment ->
            fragment.constantArrayDataSegmentId.unbound.forEach { (constantArraySegment, symbol) ->
                symbol.bind(data.size)
                val integerSize = when (constantArraySegment.second) {
                    WasmI8 -> BYTE_SIZE_BYTES
                    WasmI16 -> SHORT_SIZE_BYTES
                    WasmI32 -> INT_SIZE_BYTES
                    WasmI64 -> LONG_SIZE_BYTES
                    else -> TODO("type ${constantArraySegment.second} is not implemented")
                }
                val constData = ConstantDataIntegerArray(constantArraySegment.first, integerSize)
                data.add(WasmData(WasmDataMode.Passive, constData.toBytes()))
            }
        }
    }

    private fun bindUniqueJsFunNames() {
        val jsCodeCounter = mutableMapOf<String, Int>()
        wasmCompiledFileFragments.forEach { fragment ->
            fragment.jsFuns.forEach { jsCodeSnippet ->
                val jsFunName = jsCodeSnippet.value.importName.owner
                val counterValue = jsCodeCounter.getOrPut(jsFunName, defaultValue = { 0 })
                jsCodeCounter[jsFunName] = counterValue + 1
                if (counterValue > 0) {
                    jsCodeSnippet.value.importName.bind("${jsFunName}_$counterValue")
                }
            }
        }
    }

    private fun rebindEquivalentFunctions(allDefinedFunctions: MutableMap<IdSignature, WasmFunction>) {
        val equivalentFunctions = mutableMapOf<String, WasmFunction>()
        wasmCompiledFileFragments.forEach { fragment ->
            for ((signatureString, idSignature) in fragment.equivalentFunctions) {
                val func = equivalentFunctions[signatureString]
                if (func == null) {
                    // First occurrence of the adapter, register it (if not removed by DCE).
                    equivalentFunctions[signatureString] = allDefinedFunctions[idSignature] ?: continue
                } else {
                    // Adapter already exists, remove this one and use the existing adapter.
                    allDefinedFunctions[idSignature]?.let { duplicate ->
                        fragment.exports.removeAll { it.field == duplicate }
                    }
                    fragment.jsFuns.remove(idSignature)
                    fragment.jsModuleImports.remove(idSignature)

                    // Rebind adapter function to the single instance
                    // There might not be any unbound references in case it's called only from JS side
                    allDefinedFunctions[idSignature] = func
                }
            }
        }
    }
}

fun <IrSymbolType, WasmDeclarationType : Any, WasmSymbolType : WasmSymbol<WasmDeclarationType>> bind(
    unbound: Map<IrSymbolType, WasmSymbolType>,
    defined: Map<IrSymbolType, WasmDeclarationType>
) {
    unbound.forEach { (irSymbol, wasmSymbol) ->
        if (irSymbol !in defined)
            compilationException("Can't link symbol ${irSymbolDebugDump(irSymbol)}", type = null)
        if (!wasmSymbol.isBound()) {
            wasmSymbol.bind(defined.getValue(irSymbol))
        }
    }
}

private fun irSymbolDebugDump(symbol: Any?): String =
    when (symbol) {
        is IrFunctionSymbol -> "function ${symbol.owner.fqNameWhenAvailable}"
        is IrClassSymbol -> "class ${symbol.owner.fqNameWhenAvailable}"
        else -> symbol.toString()
    }

fun alignUp(x: Int, alignment: Int): Int {
    assert(alignment and (alignment - 1) == 0) { "power of 2 expected" }
    return (x + alignment - 1) and (alignment - 1).inv()
}

data class ClassAssociatedObjects(
    val klass: Long,
    val objects: List<AssociatedObject>
)

data class AssociatedObject(
    val obj: Long,
    val getterFunc: IdSignature,
    val isExternal: Boolean,
)