/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ir.wrapWithLambdaCall2
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.ir.isInlineFunctionCall
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.util.invokeFunction
import org.jetbrains.kotlin.ir.util.isInlineParameter
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideOrSelf

class UpdateOriginsForInlinableFunctionReferences(val context: JvmBackendContext) : FileLoweringPass,
    IrElementTransformerVoidWithContext() {
    override fun lower(irFile: IrFile) = irFile.transformChildrenVoid()

    override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
        expression.transformChildrenVoid()
        val function = expression.symbol.owner
        if (function.resolveFakeOverrideOrSelf().isInlineFunctionCall(context)) {
            val function = expression.symbol.owner
            for (parameter in function.parameters) {
                if (parameter.isInlineParameter()) {
                    val arg = expression.arguments[parameter]
                    if (arg is IrRichFunctionReference) {
                        arg.origin = IrStatementOrigin.INLINE_LAMBDA
                        arg.invokeFunction.origin = IrDeclarationOrigin.INLINE_LAMBDA
                    }
                    if (arg is IrRichPropertyReference) {
                        expression.arguments[parameter] = arg.invokeFunction.wrapWithLambdaCall2(currentDeclarationParent!!, context).also {
                            it.origin = IrStatementOrigin.INLINE_LAMBDA
                            it.invokeFunction.origin = IrDeclarationOrigin.INLINE_LAMBDA
                            it.boundValues += arg.boundValues
                        }
                    }
                }
            }
        }
        return expression
    }
}