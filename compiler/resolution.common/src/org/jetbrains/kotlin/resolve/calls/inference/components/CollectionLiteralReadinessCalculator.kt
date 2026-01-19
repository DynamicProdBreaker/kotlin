/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.inference.components

import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintKind
import org.jetbrains.kotlin.resolve.calls.model.CollectionLiteralAtomMarker
import org.jetbrains.kotlin.types.model.TypeVariableTypeConstructorMarker
import org.jetbrains.kotlin.types.model.typeConstructor
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue

context(c: VariableFixationFinder.Context)
fun CollectionLiteralAtomMarker.getReadiness(): CollectionLiteralReadiness {
    if (analyzed) return CollectionLiteralReadiness.FORBIDDEN
    val typeConstructor = expectedType?.typeConstructor()
        ?: return CollectionLiteralReadiness.FALLBACK_ONLY

    if (typeConstructor !is TypeVariableTypeConstructorMarker) {
        return CollectionLiteralReadiness.NON_TV_EXPECTED
    }

    val constraints = c.notFixedTypeVariables[typeConstructor]?.constraints
        ?: return CollectionLiteralReadiness.FALLBACK_ONLY

    val properConstraints = constraints.filter { it.isProperArgumentConstraint() }
    properConstraints.any { it.kind == ConstraintKind.EQUALITY }.ifTrue {
        return CollectionLiteralReadiness.HAS_EQUAL_CONSTRAINTS
    }

    properConstraints.any { it.kind == ConstraintKind.UPPER }.ifTrue {
        return CollectionLiteralReadiness.HAS_UPPER_CONSTRAINTS
    }

    properConstraints.ifNotEmpty {
        return CollectionLiteralReadiness.HAS_LOWER_CONSTRAINTS
    }

    return CollectionLiteralReadiness.FALLBACK_ONLY
}

class CollectionLiteralForFixation(
    val collectionLiteral: CollectionLiteralAtomMarker,
    val readiness: CollectionLiteralReadiness,
)

enum class CollectionLiteralReadiness {
    FORBIDDEN,
    FALLBACK_ONLY,
    HAS_LOWER_CONSTRAINTS,
    HAS_UPPER_CONSTRAINTS,
    HAS_EQUAL_CONSTRAINTS,
    NON_TV_EXPECTED,
    ;
}