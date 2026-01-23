/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.llvm

import kotlinx.cinterop.*
import llvm.*
import org.jetbrains.kotlin.backend.konan.driver.NativeBackendPhaseContext
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.declarations.IrFunction

internal interface RuntimeAware {
    val runtime: Runtime
}

internal class Runtime(
        phaseContext: NativeBackendPhaseContext,
        private val llvmContext: LLVMContextRef,
        bitcodeFile: String
) {
    val llvmModule: LLVMModuleRef = parseBitcodeFile(phaseContext, phaseContext.messageCollector, llvmContext, bitcodeFile)
    val calculatedLLVMTypes: MutableMap<IrType, LLVMTypeRef> = HashMap()
    val addedLLVMExternalFunctions: MutableMap<IrFunction, LlvmCallable> = HashMap()

    private fun getStructTypeOrNull(name: String, isClass: Boolean = false) =
            LLVMGetTypeByName(llvmModule, "${if (isClass) "class" else "struct"}.$name")
                    ?: LLVMGetNamedGlobal(llvmModule, "touch$name")?.let(::LLVMGlobalGetValueType)

    private fun getStructType(name: String, isClass: Boolean = false) = getStructTypeOrNull(name, isClass)
            ?: error("type $name is not found in the Runtime module.")

    private fun createStructType(name: String, vararg fieldTypes: LLVMTypeRef): LLVMTypeRef {
        val result = LLVMStructCreateNamed(llvmContext, name) ?: error("failed to create struct $name")
        LLVMStructSetBody(result, fieldTypes.toList().toCValues(), fieldTypes.size, 0)
        return result
    }

    private fun createOpaqueStructType(name: String): LLVMTypeRef =
            LLVMStructCreateNamed(llvmContext, name) ?: error("failed to create struct $name")

    val pointerType = LLVMPointerTypeInContext(llvmContext, 0)!!
    val typeInfoType = getStructType("TypeInfo")
    val extendedTypeInfoType = getStructType("ExtendedTypeInfo")
    val writableTypeInfoType = getStructTypeOrNull("WritableTypeInfo")
    val interfaceTableRecordType = getStructType("InterfaceTableRecord")
    val associatedObjectTableRecordType = getStructType("AssociatedObjectTableRecord")

    val objHeaderType = getStructType("ObjHeader")
    val arrayHeaderType = getStructType("ArrayHeader")
    val stringHeaderType = getStructType("StringHeader")

    val frameOverlayType = getStructType("FrameOverlay")

    val initNodeType = getStructType("InitNode")
    val memoryStateType = getStructTypeOrNull("MemoryState") ?: createOpaqueStructType("struct.MemoryState")

    val target = LLVMGetTarget(llvmModule)!!.toKString()

    val dataLayout = LLVMGetDataLayout(llvmModule)!!.toKString()

    val targetData = LLVMCreateTargetData(dataLayout)!!

    val kotlinObjCClassData by lazy { getStructType("KotlinObjCClassData") }
    val kotlinObjCClassInfo by lazy { getStructType("KotlinObjCClassInfo") }
    val objCMethodDescription by lazy { getStructType("ObjCMethodDescription") }
    val objCTypeAdapter by lazy { getStructType("ObjCTypeAdapter") }
    val objCToKotlinMethodAdapter by lazy { getStructType("ObjCToKotlinMethodAdapter") }
    val kotlinToObjCMethodAdapter by lazy { getStructType("KotlinToObjCMethodAdapter") }
    val typeInfoObjCExportAddition by lazy { getStructType("TypeInfoObjCExportAddition") }

    val objCClassObjectType: LLVMTypeRef by lazy {
        val result = LLVMStructCreateNamed(llvmContext, "_class_t") ?: error("failed to create struct _class_t")
        val fieldTypes = listOf(
                pointerType, // _class_t*
                pointerType, // _class_t*
                pointerType, // _objc_cache*
                pointerType, // (*)(char*, BOOL, char*, char*)
                pointerType, // _class_ro_t*
        )
        LLVMStructSetBody(result, fieldTypes.toList().toCValues(), fieldTypes.size, 0)
        result
    }
    val objCCache by lazy { createOpaqueStructType("_objc_cache") }
    val objCClassRoType by lazy {
        createStructType(
                "_class_ro_t",
                i32,
                i32,
                i32,
                pointerType, // char*
                pointerType, // char*
                pointerType, // __method_list_t*
                pointerType, // _objc_protocol_list*
                pointerType, // _ivar_list_t*
                pointerType, // char*
                pointerType, // _prop_list_t*
        )
    }
    val objCMethodType by lazy {
        createStructType("_objc_method", pointerType, pointerType, pointerType)
    }

    private val i32 = LLVMInt32TypeInContext(llvmContext)!!

    val objCMethodListType by lazy { createOpaqueStructType("__method_list_t") }
    val objCProtocolListType by lazy { createOpaqueStructType("_objc_protocol_list") }
    val objCIVarListType by lazy { createOpaqueStructType("_ivar_list_t") }
    val objCPropListType by lazy { createOpaqueStructType("_prop_list_t") }

    val blockLiteralType by lazy { getStructType("Block_literal_1") }
    val blockDescriptorType by lazy { getStructType("Block_descriptor_1") }

    fun sizeOf(type: LLVMTypeRef) = LLVMABISizeOfType(targetData, type).toInt()
    fun alignOf(type: LLVMTypeRef) = LLVMABIAlignmentOfType(targetData, type)
    fun offsetOf(type: LLVMTypeRef, index: Int) = LLVMOffsetOfElement(targetData, type, index).toInt()

    val pointerSize: Int by lazy { sizeOf(pointerType) }
    val pointerAlignment: Int by lazy { alignOf(pointerType) }

    val stringHeaderExtraSize: Int by lazy {
        offsetOf(stringHeaderType, LLVMCountStructElementTypes(stringHeaderType) - 1) - sizeOf(arrayHeaderType)
    }

    // Must match kObjectAlignment in runtime
    val objectAlignment = 8

    val isBigEndian: Boolean by lazy { LLVMByteOrder(targetData) == LLVMByteOrdering.LLVMBigEndian }
}
