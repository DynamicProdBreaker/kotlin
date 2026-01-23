/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.the
import org.jetbrains.kotlin.build.d8.D8Extension

fun Test.useJsIrBoxTests(
    buildDir: Provider<Directory>,
    fullStdLib: String = "libraries/stdlib/build/classes/kotlin/js/main",
    reducedStdlibPath: String = "libraries/stdlib/js-ir-minimal-for-test/build/classes/kotlin/js/main",
    domApiCompatPath: String = "libraries/kotlin-dom-api-compat/build/classes/kotlin/js/main"
) {
    with(project.the<D8Extension>()) {
        setupV8()
    }

    systemProperty("kotlin.js.full.stdlib.path", fullStdLib)
    systemProperty("kotlin.js.reduced.stdlib.path", reducedStdlibPath)
    systemProperty("kotlin.js.dom.api.compat", domApiCompatPath)

    systemProperty("kotlin.js.test.root.out.dir", "${buildDir.get().asFile.relativeTo(project.projectDir)}/")

    jvmArgumentProviders += project.objects.newInstance<SystemPropertyClasspathProvider>().apply {
        classpath.from(project.rootDir.resolve("js/js.tests/testFixtures/org/jetbrains/kotlin/js/engine/repl.js"))
        property.set("javascript.engine.path.repl")
    }
}
