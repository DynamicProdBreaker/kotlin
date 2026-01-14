/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.inference.components

import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintKind
import org.jetbrains.kotlin.resolve.calls.model.CollectionLiteralAtomMarker
import org.jetbrains.kotlin.types.model.TypeVariableTypeConstructorMarker
import org.jetbrains.kotlin.types.model.typeConstructor
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue

class CollectionLiteralReadinessCalculator(
    trivialConstraintTypeInferenceOracle: TrivialConstraintTypeInferenceOracle,
    languageVersionSettings: LanguageVersionSettings,
) : AbstractReadinessCalculator<CollectionLiteralAtomMarker, CollectionLiteralReadiness, CollectionLiteralForFixation>(
    trivialConstraintTypeInferenceOracle,
    languageVersionSettings,
) {
    private typealias Readiness = CollectionLiteralReadiness

    context(c: VariableFixationFinder.Context)
    override fun CollectionLiteralAtomMarker.getReadiness(dependencyProvider: TypeVariableDependencyInformationProvider): Readiness {
        if (analyzed) return Readiness.FORBIDDEN
        val typeConstructor = expectedType?.typeConstructor()
            ?: return Readiness.FALLBACK_ONLY

        if (typeConstructor !is TypeVariableTypeConstructorMarker) {
            return Readiness.NON_TV_EXPECTED
        }

        val constraints = c.notFixedTypeVariables[typeConstructor]?.constraints
            ?: return Readiness.FALLBACK_ONLY

        val properConstraints = constraints.filter { it.isProperArgumentConstraint() }
        properConstraints.any { it.kind == ConstraintKind.EQUALITY }.ifTrue {
            return Readiness.HAS_EQUAL_CONSTRAINTS
        }

        properConstraints.any { it.kind == ConstraintKind.UPPER }.ifTrue {
            return Readiness.HAS_UPPER_CONSTRAINTS
        }

        properConstraints.ifNotEmpty {
            return Readiness.HAS_LOWER_CONSTRAINTS
        }

        return Readiness.FALLBACK_ONLY
    }

    context(c: VariableFixationFinder.Context)
    override fun prepareForFixation(
        candidate: CollectionLiteralAtomMarker,
        dependencyProvider: TypeVariableDependencyInformationProvider
    ): CollectionLiteralForFixation? {
        val readiness = candidate.getReadiness(dependencyProvider).takeUnless { it == Readiness.FORBIDDEN } ?: return null
        return CollectionLiteralForFixation(candidate, readiness)
    }
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