/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.ir2wasm

import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.wasm.ir.WasmHeapType
import org.jetbrains.kotlin.wasm.ir.WasmImmediate

private const val syntheticFqName = "__SYNTHETIC__"

object Synthetics {
    // FUNCTIONS
    object Functions {
        val createStringLiteralLatin1 =
            WasmImmediate.FuncIdx(IdSignature.CommonSignature(syntheticFqName, "createStringLiteralLatin1", null, 0, null))
        val createStringLiteralUtf16 =
            WasmImmediate.FuncIdx(IdSignature.CommonSignature(syntheticFqName, "createStringLiteralUtf16", null, 0, null))
        val fieldInitializerFunction =
            WasmImmediate.FuncIdx(IdSignature.CommonSignature(syntheticFqName, "fieldInitializerFunction", null, 0, null))
        val tryGetAssociatedObjectAndWrapper =
            WasmImmediate.FuncIdx(IdSignature.CommonSignature(syntheticFqName, "tryGetAssociatedObjectAndWrapper", null, 0, null))
        val startUnitTestsFunction =
            WasmImmediate.FuncIdx(IdSignature.CommonSignature(syntheticFqName, "startUnitTestsFunction", null, 0, null))
        val masterInitFunction =
            WasmImmediate.FuncIdx(IdSignature.CommonSignature(syntheticFqName, "masterInitFunction", null, 0, null))
    }

    // GLOBALS
    object Globals {
        val addressesAndLengthsGlobal =
            WasmImmediate.GlobalIdx.FieldIdx(IdSignature.CommonSignature(syntheticFqName, "addressesAndLengthsGlobal", null, 0, null))
        val stringPoolGlobal =
            WasmImmediate.GlobalIdx.FieldIdx(IdSignature.CommonSignature(syntheticFqName, "stringPoolGlobal", null, 0, null))
    }

    // GC TYPES
    private val wasmAnyArrayTypeSignature =
        IdSignature.CommonSignature(syntheticFqName, "wasmAnyArrayType", null, 0, null)
    private val specialSlotITableTypeSignature =
        IdSignature.CommonSignature(syntheticFqName, "specialSlotITableType", null, 0, null)
    private val rttiTypeSignature =
        IdSignature.CommonSignature(syntheticFqName, "rttiType", null, 0, null)
    private val wasmLongArraySignature =
        IdSignature.CommonSignature(syntheticFqName, "wasmLongArray", null, 0, null)
    private val associatedObjectGetterTypeSignature =
        IdSignature.CommonSignature(syntheticFqName, "associatedObjectGetterType", null, 0, null)
    private val wasmLongArrayDeclarationSignature =
        IdSignature.CommonSignature(syntheticFqName, "wasmLongArrayDeclaration", null, 0, null)
    private val wasmStringArrayTypeSignature =
        IdSignature.CommonSignature(syntheticFqName, "wasmStringArrayType", null, 0, null)
    private val byteArraySignature =
        IdSignature.CommonSignature(syntheticFqName, "byteArray", null, 0, null)

    object HeapTypes {
        val wasmAnyArrayType = WasmImmediate.HeapType(WasmHeapType.Type(wasmAnyArrayTypeSignature))
        val specialSlotITableType = WasmImmediate.HeapType(WasmHeapType.Type(specialSlotITableTypeSignature))
        val rttiType = WasmImmediate.HeapType(WasmHeapType.Type(rttiTypeSignature))
        val wasmLongArray = WasmImmediate.HeapType(WasmHeapType.Type(wasmLongArraySignature))
        val associatedObjectGetterType = WasmImmediate.HeapType(WasmHeapType.Type(associatedObjectGetterTypeSignature))
        val wasmLongArrayDeclaration = WasmImmediate.HeapType(WasmHeapType.Type(wasmLongArrayDeclarationSignature))
        val wasmStringArrayType = WasmImmediate.HeapType(WasmHeapType.Type(wasmStringArrayTypeSignature))
        val byteArray = WasmImmediate.HeapType(WasmHeapType.Type(byteArraySignature))
    }

    object GcTypes {
        val wasmAnyArrayType = WasmImmediate.TypeIdx.GcTypeIdx(wasmAnyArrayTypeSignature)
        val specialSlotITableType = WasmImmediate.TypeIdx.GcTypeIdx(specialSlotITableTypeSignature)
        val rttiType = WasmImmediate.TypeIdx.GcTypeIdx(rttiTypeSignature)
        val wasmLongArray = WasmImmediate.TypeIdx.GcTypeIdx(wasmLongArraySignature)
        val associatedObjectGetterType = WasmImmediate.TypeIdx.GcTypeIdx(associatedObjectGetterTypeSignature)
        val wasmLongArrayDeclaration = WasmImmediate.TypeIdx.GcTypeIdx(wasmLongArrayDeclarationSignature)
        val wasmStringArrayType = WasmImmediate.TypeIdx.GcTypeIdx(wasmStringArrayTypeSignature)
        val byteArray = WasmImmediate.TypeIdx.GcTypeIdx(byteArraySignature)
    }

    // FUNCTION TYPES
    private val associatedObjectGetterWrapperSignature =
        IdSignature.CommonSignature(syntheticFqName, "associatedObjectGetterWrapper", null, 0, null)
    private val stringLiteralFunctionTypeSignature =
        IdSignature.CommonSignature(syntheticFqName, "stringLiteralFunctionType", null, 0, null)

    object FunctionGcTypes {
        val associatedObjectGetterWrapper = WasmImmediate.TypeIdx.FunctionTypeIdx(associatedObjectGetterWrapperSignature)
        val stringLiteralFunctionType = WasmImmediate.TypeIdx.FunctionTypeIdx(stringLiteralFunctionTypeSignature)
    }

    object FunctionHeapTypes {
        val associatedObjectGetterWrapper = WasmImmediate.HeapType(WasmHeapType.FunctionType(associatedObjectGetterWrapperSignature))
        val stringLiteralFunctionType = WasmImmediate.HeapType(WasmHeapType.FunctionType(stringLiteralFunctionTypeSignature))
    }

}