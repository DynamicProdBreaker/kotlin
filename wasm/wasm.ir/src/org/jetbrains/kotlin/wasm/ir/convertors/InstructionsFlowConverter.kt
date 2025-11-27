/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.wasm.ir.convertors

import org.jetbrains.kotlin.wasm.ir.WasmInstr

private fun createInstructionsFlow(input: NullTerminatedInstructionFlow): NullTerminatedInstructionFlow {
    val removedUnreachableCode = RemoveUnreachableInstructions(input)
    val mergedWithDrop = RemoveInstructionPriorDrop(removedUnreachableCode)
    val mergedWithUnreachable = RemoveInstructionPriorUnreachable(mergedWithDrop)
    val mergedWithTee = MergeSetAndGetIntoTee(mergedWithUnreachable)
    return mergedWithTee
}

private class FlowWithAdditionalInstruction : NullTerminatedInstructionFlow() {
    var currentIterator: Iterator<WasmInstr> = emptyList<WasmInstr>().iterator()
    var lastInstruction: WasmInstr? = null

    override fun pullNext(): WasmInstr? {
        if (currentIterator.hasNext()) {
            return currentIterator.next()
        }

        lastInstruction?.let { toEmit ->
            lastInstruction = null
            return toEmit
        }
        return null
    }
}

internal class InstructionOptimizer {
    // This is null-terminated flow. It yields current flow to optimize and then additional instruction and
    // then trigger null, which makes all instruction flow to stop.
    private val inputWithAdditional = FlowWithAdditionalInstruction()

    private val optimizeOutput = createInstructionsFlow(inputWithAdditional)

    fun optimize(sequence: Iterable<WasmInstr>, handler: (WasmInstr) -> Unit) {
        optimize(sequence, completeInstruction = null, handler)
    }

    // This functions can run new instruction flow for optimization. It built to reuse all optimization flow
    // to avoid making the optimizers on each separate instruction flow.
    // As far as the output is null - the current flow considered to be completed.
    fun optimize(instructions: Iterable<WasmInstr>, completeInstruction: WasmInstr? = null, handler: (WasmInstr) -> Unit) {
        inputWithAdditional.currentIterator = instructions.iterator()
        inputWithAdditional.lastInstruction = completeInstruction

        while (true) {
            val instr = optimizeOutput.pullNext() ?: break
            handler(instr)
        }
    }
}