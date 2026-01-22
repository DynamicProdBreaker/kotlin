/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower.inline

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.PreSerializationLoweringContext
import org.jetbrains.kotlin.backend.common.serialization.DeclarationTable
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.symbols.IrFileSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.IrTreeSymbolsVisitor
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.acceptVoid

class SignaturesComputationLowering(val context: PreSerializationLoweringContext) : FileLoweringPass {
    private val declarationTable: DeclarationTable<*> = context.declarationTable
    private var isDeclared = false

    private val visitor = object : IrTreeSymbolsVisitor() {
        override fun visitDeclaredSymbol(container: IrElement, symbol: IrSymbol) {
            val prev = isDeclared
            isDeclared = true
            super.visitDeclaredSymbol(container, symbol)
            isDeclared = prev
        }

        override fun visitReferencedSymbol(container: IrElement, symbol: IrSymbol) {
            val prev = isDeclared
            isDeclared = false
            super.visitReferencedSymbol(container, symbol)
            isDeclared = prev
        }

        override fun visitSymbol(container: IrElement, symbol: IrSymbol) {
            if (!symbol.isBound) return
            if (symbol is IrFileSymbol) return

            // Compute the signature:
            when (val symbolOwner = symbol.owner) {
                is IrDeclaration -> declarationTable.signatureByDeclaration(
                    declaration = symbolOwner,
                    compatibleMode = false,
                    recordInSignatureClashDetector = isDeclared,
                )
                is IrReturnableBlock -> declarationTable.signatureByReturnableBlock(symbolOwner)
                else -> error("Expected symbol owner: ${symbolOwner.render()}")
            }
        }
    }

    override fun lower(irFile: IrFile) {
        declarationTable.inFile(irFile) {
            irFile.acceptVoid(visitor)
        }
    }
}
