/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.ir2wasm

import org.jetbrains.kotlin.backend.common.serialization.cityHash64
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.ir.declarations.IdSignatureRetriever
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.wasm.ir.*
import org.jetbrains.kotlin.wasm.ir.WasmImmediate

enum class WasmServiceImportExportKind(val prefix: String) {
    VTABLE($$"__vt$"),
    ITABLE($$"__it$"),
    RTTI($$"$__rt$"),
    FUNC($$"__fn$")
}

open class WasmFileCodegenContext(
    private val wasmFileFragment: WasmCompiledFileFragment,
    protected val idSignatureRetriever: IdSignatureRetriever,
) {
    open fun handleFunctionWithImport(declaration: IrFunctionSymbol): Boolean = false
    open fun handleVTableWithImport(declaration: IrClassSymbol): Boolean = false
    open fun handleClassITableWithImport(declaration: IrClassSymbol): Boolean = false
    open fun handleRTTIWithImport(declaration: IrClassSymbol, superType: IrClassSymbol?): Boolean = false


    ///TODO REMOVE???
    private val wasmRefNullTypeCache = mutableMapOf<IrClassSymbol, WasmRefNullType>()
    fun getCachedRefNullType(symbol: IrClassSymbol): WasmRefNullType =
        wasmRefNullTypeCache.getOrPut(symbol) {
            WasmRefNullType(referenceHeapType(symbol).value)
        }

    private fun IrSymbol.getReferenceKey(): IdSignature =
        idSignatureRetriever.declarationSignature(this.owner as IrDeclaration)!!

    fun referenceStringLiteralId(string: String): WasmSymbol<Int> =
        wasmFileFragment.stringLiteralId.reference(string)

    fun referenceConstantArray(resource: Pair<List<Long>, WasmType>): WasmSymbol<Int> =
        wasmFileFragment.constantArrayDataSegmentId.reference(resource)

    fun addExport(wasmExport: WasmExport<*>) {
        wasmFileFragment.exports += wasmExport
    }

    private fun IrClassSymbol.getSignature(): IdSignature =
        idSignatureRetriever.declarationSignature(this.owner)!!

    open fun defineFunction(irFunction: IrFunctionSymbol, wasmFunction: WasmFunction) {
        wasmFileFragment.definedFunctions[irFunction.getReferenceKey()] = wasmFunction
    }

    open fun defineGlobalField(irField: IrFieldSymbol, wasmGlobal: WasmGlobal) {
        wasmFileFragment.definedGlobalFields[irField.getReferenceKey()] = wasmGlobal
    }

    open fun defineGlobalVTable(irClass: IrClassSymbol, wasmGlobal: WasmGlobal) {
        wasmFileFragment.definedGlobalVTables[irClass.getReferenceKey()] = wasmGlobal
    }

    open fun defineGlobalClassITable(irClass: IrClassSymbol, wasmGlobal: WasmGlobal) {
        wasmFileFragment.definedGlobalClassITables[irClass.getReferenceKey()] = wasmGlobal
    }

    open fun defineRttiGlobal(global: WasmGlobal, irClass: IrClassSymbol, irSuperClass: IrClassSymbol?) {
        val reference = irClass.getReferenceKey()
        wasmFileFragment.definedRttiGlobal[reference] = global
        wasmFileFragment.definedRttiSuperType[reference] = irSuperClass?.getReferenceKey()
    }

    fun defineGcType(irClass: IrClassSymbol, wasmType: WasmTypeDeclaration) {
        wasmFileFragment.definedGcTypes[irClass.getReferenceKey()] = wasmType
    }

    fun defineVTableGcType(irClass: IrClassSymbol, wasmType: WasmTypeDeclaration) {
        wasmFileFragment.definedVTableGcTypes[irClass.getReferenceKey()] = wasmType
    }

    fun defineFunctionType(irFunction: IrFunctionSymbol, wasmFunctionType: WasmFunctionType) {
        wasmFileFragment.definedFunctionTypes[irFunction.getReferenceKey()] = wasmFunctionType
    }

    open fun referenceFunction(irFunction: IrFunctionSymbol): WasmImmediate.FuncIdx =
        WasmImmediate.FuncIdx(irFunction.getReferenceKey())

    open fun referenceGlobalField(irField: IrFieldSymbol): WasmImmediate.GlobalIdx.FieldIdx =
        WasmImmediate.GlobalIdx.FieldIdx(irField.getReferenceKey())

    open fun referenceGlobalVTable(irClass: IrClassSymbol): WasmImmediate.GlobalIdx.VTableIdx =
        WasmImmediate.GlobalIdx.VTableIdx(irClass.getReferenceKey())

    open fun referenceGlobalClassITable(irClass: IrClassSymbol): WasmImmediate.GlobalIdx.ClassITableIdx =
        WasmImmediate.GlobalIdx.ClassITableIdx(irClass.getReferenceKey())

    open fun referenceRttiGlobal(irClass: IrClassSymbol): WasmImmediate.GlobalIdx.RttiIdx =
        WasmImmediate.GlobalIdx.RttiIdx(irClass.getReferenceKey())

    open fun referenceGcType(irClass: IrClassSymbol): WasmImmediate.TypeIdx.GcTypeIdx =
        WasmImmediate.TypeIdx.GcTypeIdx(irClass.getReferenceKey())

    open fun referenceHeapType(irClass: IrClassSymbol): WasmImmediate.HeapType =
        WasmImmediate.HeapType(WasmHeapType.Type(irClass.getReferenceKey()))

    open fun referenceVTableGcType(irClass: IrClassSymbol): WasmImmediate.TypeIdx.VTableTypeIdx =
        WasmImmediate.TypeIdx.VTableTypeIdx(irClass.getReferenceKey())

    open fun referenceVTableHeapType(irClass: IrClassSymbol): WasmImmediate.HeapType =
        WasmImmediate.HeapType(WasmHeapType.Type(irClass.getReferenceKey()))

    open fun referenceFunctionType(irClass: IrFunctionSymbol): WasmImmediate.TypeIdx.FunctionTypeIdx =
        WasmImmediate.TypeIdx.FunctionTypeIdx(irClass.getReferenceKey())

    open fun referenceFunctionHeapType(irClass: IrFunctionSymbol): WasmImmediate.HeapType =
        WasmImmediate.HeapType(WasmHeapType.Type(irClass.getReferenceKey()))

    fun referenceTypeId(irClass: IrClassSymbol): Long =
        cityHash64(irClass.getSignature().toString().encodeToByteArray()).toLong()

    fun addJsFun(irFunction: IrFunctionSymbol, importName: WasmSymbol<String>, jsCode: String) {
        wasmFileFragment.jsFuns[irFunction.getReferenceKey()] =
            WasmCompiledModuleFragment.JsCodeSnippet(importName = importName, jsCode = jsCode)
    }

    fun addJsModuleImport(irFunction: IrFunctionSymbol, module: String) {
        wasmFileFragment.jsModuleImports[irFunction.getReferenceKey()] = module
    }

    fun addJsBuiltin(declarationName: String, polyfillImpl: String) {
        wasmFileFragment.jsBuiltinsPolyfills[declarationName] = polyfillImpl
    }

    fun addObjectInstanceFieldInitializer(initializer: IrFunctionSymbol) {
        wasmFileFragment.objectInstanceFieldInitializers.add(initializer.getReferenceKey())
    }

    fun addNonConstantFieldInitializers(initializer: IrFunctionSymbol) {
        wasmFileFragment.nonConstantFieldInitializers.add(initializer.getReferenceKey())
    }

    fun addMainFunctionWrapper(mainFunctionWrapper: IrFunctionSymbol) {
        wasmFileFragment.mainFunctionWrappers.add(mainFunctionWrapper.getReferenceKey())
    }

    fun addTestFunDeclarator(testFunctionDeclarator: IrFunctionSymbol) {
        wasmFileFragment.testFunctionDeclarators.add(testFunctionDeclarator.getReferenceKey())
    }

    fun addEquivalentFunction(key: String, function: IrFunctionSymbol) {
        wasmFileFragment.equivalentFunctions.add(key to function.getReferenceKey())
    }

    fun addClassAssociatedObjects(klass: IrClassSymbol, associatedObjectsGetters: List<AssociatedObjectBySymbols>) {
        val classAssociatedObjects = ClassAssociatedObjects(
            referenceTypeId(klass),
            associatedObjectsGetters.map { (obj, getter, isExternal) ->
                AssociatedObject(referenceTypeId(obj), getter.getReferenceKey(), isExternal)
            }
        )
        wasmFileFragment.classAssociatedObjectsInstanceGetters.add(classAssociatedObjects)
    }

    fun addJsModuleAndQualifierReferences(reference: JsModuleAndQualifierReference) {
        wasmFileFragment.jsModuleAndQualifierReferences.add(reference)
    }

    fun defineBuiltinIdSignatures(
        throwable: IrClassSymbol?,
        kotlinAny: IrClassSymbol?,
        tryGetAssociatedObject: IrFunctionSymbol?,
        jsToKotlinAnyAdapter: IrFunctionSymbol?,
        unitGetInstance: IrFunctionSymbol?,
        runRootSuites: IrFunctionSymbol?,
        createString: IrFunctionSymbol?,
        registerModuleDescriptor: IrFunctionSymbol?,
    ) {
        if (throwable != null || kotlinAny != null || tryGetAssociatedObject != null || jsToKotlinAnyAdapter != null || unitGetInstance != null || runRootSuites != null || createString != null || registerModuleDescriptor != null) {
            val originalSignatures = wasmFileFragment.builtinIdSignatures
            wasmFileFragment.builtinIdSignatures = BuiltinIdSignatures(
                throwable = originalSignatures?.throwable
                    ?: throwable?.getReferenceKey(),
                kotlinAny = originalSignatures?.kotlinAny
                    ?: kotlinAny?.getReferenceKey(),
                tryGetAssociatedObject = originalSignatures?.tryGetAssociatedObject
                    ?: tryGetAssociatedObject?.getReferenceKey(),
                jsToKotlinAnyAdapter = originalSignatures?.jsToKotlinAnyAdapter
                    ?: jsToKotlinAnyAdapter?.getReferenceKey(),
                unitGetInstance = originalSignatures?.unitGetInstance
                    ?: unitGetInstance?.getReferenceKey(),
                runRootSuites = originalSignatures?.runRootSuites
                    ?: runRootSuites?.getReferenceKey(),
                createString = originalSignatures?.createString
                    ?: createString?.getReferenceKey(),
                registerModuleDescriptor = originalSignatures?.registerModuleDescriptor
                    ?: registerModuleDescriptor?.getReferenceKey(),
            )
        }
    }
}

class WasmModuleMetadataCache(private val backendContext: WasmBackendContext) {
    private val interfaceMetadataCache = mutableMapOf<IrClassSymbol, InterfaceMetadata>()
    fun getInterfaceMetadata(irClass: IrClassSymbol): InterfaceMetadata =
        interfaceMetadataCache.getOrPut(irClass) { InterfaceMetadata(irClass.owner, backendContext.irBuiltIns) }

    private val classMetadataCache = mutableMapOf<IrClassSymbol, ClassMetadata>()
    fun getClassMetadata(irClass: IrClassSymbol): ClassMetadata =
        classMetadataCache.getOrPut(irClass) {
            val superClass = irClass.owner.getSuperClass(backendContext.irBuiltIns)
            val superClassMetadata = superClass?.let { getClassMetadata(it.symbol) }
            ClassMetadata(
                klass = irClass.owner,
                superClass = superClassMetadata,
                irBuiltIns = backendContext.irBuiltIns,
                allowAccidentalOverride = backendContext.partialLinkageSupport.isEnabled
            )
        }
}

class WasmModuleTypeTransformer(
    backendContext: WasmBackendContext,
    wasmFileCodegenContext: WasmFileCodegenContext,
) {
    private val typeTransformer =
        WasmTypeTransformer(backendContext, wasmFileCodegenContext)

    fun transformType(irType: IrType): WasmType {
        return with(typeTransformer) { irType.toWasmValueType() }
    }

    fun transformFieldType(irType: IrType): WasmType {
        return with(typeTransformer) { irType.toWasmFieldType() }
    }

    fun transformBoxedType(irType: IrType): WasmType {
        return with(typeTransformer) { irType.toBoxedInlineClassType() }
    }

    fun transformValueParameterType(irValueParameter: IrValueParameter): WasmType {
        return with(typeTransformer) {
            if (backendContext.inlineClassesUtils.shouldValueParameterBeBoxed(irValueParameter)) {
                irValueParameter.type.toBoxedInlineClassType()
            } else {
                irValueParameter.type.toWasmValueType()
            }
        }
    }

    fun transformResultType(irType: IrType): WasmType? {
        return with(typeTransformer) { irType.toWasmResultType() }
    }

    fun transformBlockResultType(irType: IrType): WasmType? {
        return with(typeTransformer) { irType.toWasmBlockResultType() }
    }
}

data class AssociatedObjectBySymbols(val klass: IrClassSymbol, val getter: IrFunctionSymbol, val isExternal: Boolean)