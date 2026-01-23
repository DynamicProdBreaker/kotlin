/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.analysis.api.fir.test

import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.impl.base.symbols.pointers.KaBasePsiSymbolPointer
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.low.level.api.fir.test.configurators.AnalysisApiFirSourceTestConfigurator
import org.jetbrains.kotlin.analysis.test.framework.base.AbstractAnalysisApiExecutionTest
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class EnumEntryConstructorPointerTest : AbstractAnalysisApiExecutionTest("analysis/analysis-api-fir/testData/symbols") {
    override val configurator = AnalysisApiFirSourceTestConfigurator(analyseInDependentSession = false)

    /**
     * Regression test for KT-68633: Specifically tests the IGNORE_SELF dangling file resolution mode
     * which triggers the problematic code path in KaFirSymbolRelationProvider.containingDeclaration.
     *
     * The original bug was triggered when:
     * 1. A file copy is analyzed in IGNORE_SELF mode
     * 2. containingDeclaration uses getContainingDeclarationByPsi() for declarations in dangling files
     * 3. For enum entry constructors, this returned KaEnumEntrySymbol instead of KaClassSymbol
     * 4. createOwnerPointer() then failed with IllegalArgumentException
     */
    @Test
    fun enumEntryWithBodyConstructorPointerInIgnoreSelfMode(mainFile: KtFile) {
        // Create a file copy to trigger the dangling file path
        val ktPsiFactory = KtPsiFactory.contextual(mainFile, markGenerated = true, eventSystemEnabled = true)
        val fileCopy = ktPsiFactory.createFile("copy.kt", mainFile.text)
        fileCopy.originalFile = mainFile

        val enumClass = fileCopy.declarations.filterIsInstance<KtClass>().first { it.name == "MyEnum" }

        // Use analyzeCopy with IGNORE_SELF mode to trigger the problematic code path
        analyzeCopy(enumClass, KaDanglingFileResolutionMode.IGNORE_SELF) {
            val enumSymbol = enumClass.classSymbol!! as KaNamedClassSymbol
            // Enum entries are in the static member scope
            val enumEntrySymbol = enumSymbol.staticMemberScope.callables
                .filterIsInstance<KaEnumEntrySymbol>()
                .single()
            val enumEntryInitializer = enumEntrySymbol.enumEntryInitializer
            assertNotNull(enumEntryInitializer, "Enum entry should have an initializer")
            // Get the implicit primary constructor of the enum entry initializer
            val constructors = enumEntryInitializer.memberScope.constructors.toList()
            assertNotNull(constructors.singleOrNull(), "Enum entry initializer should have exactly one constructor")
            val constructor = constructors.single()
            // Disable PSI-based pointers to force the non-PSI path (which is where the bug was)
            val pointer = KaBasePsiSymbolPointer.withDisabledPsiBasedPointers(disable = true) {
                // IllegalArgumentException: Expected class KaClassSymbol instead of KaFirEnumEntrySymbol
                constructor.createPointer()
            }
            // Make sure we're testing the non-PSI pointer path
            assertFalse(pointer is KaBasePsiSymbolPointer<*>, "Should use non-PSI pointer for this test")
            // Verify the pointer can be restored
            val restored = pointer.restoreSymbol()
            assertNotNull(restored, "Constructor pointer should be restorable")
        }
    }
}
