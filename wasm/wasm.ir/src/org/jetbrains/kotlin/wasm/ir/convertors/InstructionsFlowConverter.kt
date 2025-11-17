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
    private var currentIterator: Iterable<WasmInstr> = emptyList()
    private var additional: WasmInstr? = null

    private val optimizeInput = sequence {
        while (true) {
            yieldAll(currentIterator)
            yieldIfNotNull(additional)
            yield(null)
        }
    }

    private val optimizeOutput = processInstructionsFlow(optimizeInput).iterator()

    fun optimize(sequence: Iterable<WasmInstr>, handler: (WasmInstr) -> Unit) {
        optimize(sequence, completeInstruction = null, handler)
    }

    fun optimize(sequence: Iterable<WasmInstr>, completeInstruction: WasmInstr? = null, handler: (WasmInstr) -> Unit) {
        currentIterator = sequence
        additional = completeInstruction
        for (instr in optimizeOutput) {
            if (instr == null) break
            handler(instr)
        }
    }
}