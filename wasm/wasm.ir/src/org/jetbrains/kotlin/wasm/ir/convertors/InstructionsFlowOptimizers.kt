/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.wasm.ir.convertors

import org.jetbrains.kotlin.wasm.ir.*
import org.jetbrains.kotlin.wasm.ir.source.location.SourceLocation

// These optimisation flows should be null-terminated. I.e. it should support to complete optimisation as far as null comes from the flow.
// Additionally it should propagate this terminate null further to make all next optimisations in a row to be completed.
// TODO: All of those optimizations could be moved to WasmExpressionBuilder stage, so, we will not write the unreachable instructions and eliminate extra post-processing of the instruction flow
private fun WasmOp.pureStacklessInstruction() = when (this) {
    WasmOp.REF_NULL, WasmOp.I32_CONST, WasmOp.I64_CONST, WasmOp.F32_CONST, WasmOp.F64_CONST, WasmOp.LOCAL_GET, WasmOp.GLOBAL_GET, WasmOp.CALL_PURE -> true
    else -> false
}

private fun WasmOp.isOutCfgNode() = when (this) {
    WasmOp.UNREACHABLE, WasmOp.RETURN, WasmOp.THROW, WasmOp.THROW_REF, WasmOp.RETHROW, WasmOp.BR, WasmOp.BR_TABLE -> true
    else -> false
}

private fun WasmOp.isInCfgNode() = when (this) {
    WasmOp.ELSE, WasmOp.CATCH, WasmOp.CATCH_ALL -> true
    else -> false
}

internal abstract class NullTerminatedInstructionFlow {
    abstract fun pullNext(): WasmInstr?
}

internal class RemoveUnreachableInstructions(val input: NullTerminatedInstructionFlow) : NullTerminatedInstructionFlow() {
    private var eatEverythingUntilLevel: Int? = null
    private var numberOfNestedBlocks = 0

    private fun getCurrentEatLevel(op: WasmOp): Int? {
        val eatLevel = eatEverythingUntilLevel ?: return null
        if (numberOfNestedBlocks == eatLevel && op.isInCfgNode()) {
            eatEverythingUntilLevel = null
            return null
        }
        if (numberOfNestedBlocks < eatLevel) {
            eatEverythingUntilLevel = null
            return null
        }
        return eatLevel
    }

    override fun pullNext(): WasmInstr? {
        while (true) {
            val instruction = input.pullNext() ?: break

            val op = instruction.operator

            if (op.isBlockStart()) {
                numberOfNestedBlocks++
            } else if (op.isBlockEnd()) {
                numberOfNestedBlocks--
            }

            val currentEatUntil = getCurrentEatLevel(op)
            if (currentEatUntil != null) {
                if (currentEatUntil <= numberOfNestedBlocks) {
                    continue
                }
            } else {
                if (op.isOutCfgNode()) {
                    eatEverythingUntilLevel = numberOfNestedBlocks
                }
            }
            return instruction
        }
        return null
    }
}

internal class RemoveInstructionPriorUnreachable(private val input: NullTerminatedInstructionFlow) : NullTerminatedInstructionFlow() {
    private var firstInstruction: WasmInstr? = null

    override fun pullNext(): WasmInstr? {
        while (true) {
            val instruction = input.pullNext() ?: break

            if (instruction.operator.opcode == WASM_OP_PSEUDO_OPCODE) {
                return instruction
            }

            val first = firstInstruction
            firstInstruction = instruction

            if (first == null) {
                continue
            }

            if (instruction.operator == WasmOp.UNREACHABLE && (first.operator.pureStacklessInstruction() || first.operator == WasmOp.NOP)) {
                if (first.operator != WasmOp.NOP) {
                    val firstLocation = first.location as? SourceLocation.DefinedLocation
                    if (firstLocation != null) {
                        //replace first instruction to NOP
                        return wasmInstrWithLocation(WasmOp.NOP, firstLocation)
                    }
                }
            } else {
                return first
            }
        }

        firstInstruction?.let { toEmit ->
            firstInstruction = null
            return toEmit
        }
        return null
    }
}

internal class RemoveInstructionPriorDrop(private val input: NullTerminatedInstructionFlow) : NullTerminatedInstructionFlow() {
    private var firstInstruction: WasmInstr? = null
    private var secondInstruction: WasmInstr? = null

    override fun pullNext(): WasmInstr? {
        while (true) {
            val instruction = input.pullNext() ?: break

            if (instruction.operator.opcode == WASM_OP_PSEUDO_OPCODE) {
                return instruction
            }

            val first = firstInstruction
            val second = secondInstruction

            if (first == null) {
                firstInstruction = instruction
                continue
            }
            if (second == null) {
                secondInstruction = instruction
                continue
            }

            if (second.operator == WasmOp.DROP && first.operator.pureStacklessInstruction()) {
                val firstLocation = first.location as? SourceLocation.DefinedLocation
                if (firstLocation != null) {
                    //replace first instruction
                    firstInstruction = wasmInstrWithLocation(WasmOp.NOP, firstLocation)
                    secondInstruction = instruction
                } else {
                    //eat both instructions
                    firstInstruction = instruction
                    secondInstruction = null
                }
            } else {
                firstInstruction = second
                secondInstruction = instruction
                return first
            }
        }

        firstInstruction?.let { toEmit ->
            firstInstruction = null
            return toEmit
        }

        secondInstruction?.let { toEmit ->
            secondInstruction = null
            return toEmit
        }

        return null
    }
}


internal class MergeSetAndGetIntoTee(private val input: NullTerminatedInstructionFlow) : NullTerminatedInstructionFlow() {
    private var firstInstruction: WasmInstr? = null

    override fun pullNext(): WasmInstr? {
        while (true) {
            val instruction = input.pullNext() ?: break

            if (instruction.operator.opcode == WASM_OP_PSEUDO_OPCODE) {
                return instruction
            }

            val first = firstInstruction

            if (first == null) {
                firstInstruction = instruction
                continue
            }

            if (first.operator == WasmOp.LOCAL_SET && instruction.operator == WasmOp.LOCAL_GET) {
                check(first.immediatesCount == 1 && instruction.immediatesCount == 1)
                val firstImmediate = first.firstImmediateOrNull()
                val secondImmediate = instruction.firstImmediateOrNull()
                val setNumber = (firstImmediate as? WasmImmediate.LocalIdx)?.value
                val getNumber = (secondImmediate as? WasmImmediate.LocalIdx)?.value
                check(setNumber != null && getNumber != null)

                if (getNumber == setNumber) {
                    val location = instruction.location
                    firstInstruction = if (location != null) {
                        wasmInstrWithLocation(WasmOp.LOCAL_TEE, location, firstImmediate)
                    } else {
                        wasmInstrWithoutLocation(WasmOp.LOCAL_TEE, firstImmediate)
                    }
                    continue
                }
            }

            firstInstruction = instruction
            return first
        }

        firstInstruction?.let { toEmit ->
            firstInstruction = null
            return toEmit
        }
        return null
    }
}
