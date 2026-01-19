/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.inference.components

import org.jetbrains.kotlin.resolve.calls.inference.model.VariableWithConstraints
import org.jetbrains.kotlin.types.model.TypeConstructorMarker
import org.jetbrains.kotlin.types.model.TypeSystemInferenceExtensionContext

/**
 * Serves as an identifier for a constraint system.
 * In general, [org.jetbrains.kotlin.types.model.TypeSystemInferenceExtensionContext] is not an identifier,
 * as it may have singleton implementations (like `TypeComponents.typeContext`).
 *
 * [ConstraintSystemMarker] was introduced for inference logging and is used to
 * group together related constraints.
 */
interface ConstraintSystemMarker : TypeSystemInferenceExtensionContext {
    val notFixedTypeVariables: Map<TypeConstructorMarker, VariableWithConstraints>

    /**
     * If not null, that property means that we should assume temporary them all as proper types when fixating some variables.
     *
     * By default, if that property is null, we assume all `allTypeVariables` as not proper.
     *
     * Currently, that is only used for `provideDelegate` resolution, see
     * [org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirDeclarationsResolveTransformer.fixInnerVariablesForProvideDelegateIfNeeded]
     */
    val typeVariablesThatAreCountedAsProperTypes: Set<TypeConstructorMarker>?
}