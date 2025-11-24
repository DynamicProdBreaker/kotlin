/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.wasm.ir.convertors

import org.jetbrains.kotlin.utils.yieldIfNotNull
import org.jetbrains.kotlin.wasm.ir.WasmInstr

private fun processInstructionsFlow(input: Sequence<WasmInstr?>): Sequence<WasmInstr?> {
    val removedUnreachableCode = removeUnreachableInstructions(input)
    val mergedWithDrop = removeInstructionPriorDrop(removedUnreachableCode)
    val mergedWithUnreachable = removeInstructionPriorUnreachable(mergedWithDrop)
    val mergedWithTee = mergeSetAndGetIntoTee(mergedWithUnreachable)
    return mergedWithTee
}

internal class InstructionOptimizer {
    private var currentIterable: Iterable<WasmInstr> = emptyList()
    private var additional: WasmInstr? = null

    // This is null-terminated sequence. It yields current sequence to optimize and then additional instruction and
    // then trigger null, which makes all instruction sequences to terminate their flow.
    private val optimizeInput = sequence {
        while (true) {
            yieldAll(currentIterable)
            yieldIfNotNull(additional)
            yield(null)
        }
    }

    private val optimizeOutput = processInstructionsFlow(optimizeInput).iterator()

    fun optimize(sequence: Iterable<WasmInstr>, handler: (WasmInstr) -> Unit) {
        optimize(sequence, completeInstruction = null, handler)
    }

    // This functions can run new instruction sequences for optimization. It built to reuse all optimization sequences
    // to avoid the state machines creation on each separate instruction flow.
    // As far as the output is null - the current sequence considered to be completed.
    fun optimize(sequence: Iterable<WasmInstr>, completeInstruction: WasmInstr? = null, handler: (WasmInstr) -> Unit) {
        currentIterable = sequence
        additional = completeInstruction
        for (instr in optimizeOutput) {
            if (instr == null) break
            handler(instr)
        }
    }
}