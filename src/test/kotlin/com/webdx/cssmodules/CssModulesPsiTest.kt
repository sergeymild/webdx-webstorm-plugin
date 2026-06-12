package com.webdx.cssmodules

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * PSI-level tests for the [CssModules] helpers that need a real project, CSS PSI
 * and JS/TS PSI (resolution, reference search). Runs on an in-memory fixture
 * against the local WebStorm SDK (CSS + JavaScript bundled plugins).
 */
class CssModulesPsiTest : BasePlatformTestCase() {

    // --- collectClassNames -------------------------------------------------

    fun testCollectClassNamesReturnsDistinctNamesWithoutDot() {
        val scss = myFixture.addFileToProject(
            "src/Comp.module.scss",
            """
            .container { color: red; }
            .mobileWrapper { display: flex; }
            .container { font-weight: bold; }
            """.trimIndent(),
        )
        assertEquals(listOf("container", "mobileWrapper"), CssModules.collectClassNames(scss))
    }

    fun testCollectClassNamesEmptyForNoClasses() {
        val scss = myFixture.addFileToProject("src/Empty.module.scss", "body { color: red; }")
        assertEquals(emptyList<String>(), CssModules.collectClassNames(scss))
    }

    // --- resolveModuleForBinding ------------------------------------------

    fun testResolveModuleForBindingResolvesSibling() {
        myFixture.addFileToProject("src/Comp.module.scss", ".a { color: red; }")
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            "import styles from './Comp.module.scss';\nconst x = styles.a;",
        )
        val resolved = CssModules.resolveModuleForBinding(tsx, "styles")
        assertNotNull(resolved)
        assertEquals("Comp.module.scss", resolved!!.name)
    }

    fun testResolveModuleForBindingNullForUnknownBinding() {
        myFixture.addFileToProject("src/Comp.module.scss", ".a {}")
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            "import styles from './Comp.module.scss';",
        )
        assertNull(CssModules.resolveModuleForBinding(tsx, "other"))
    }

    fun testResolveModuleForBindingNullForNonModuleImport() {
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            "import styles from './plain.css';",
        )
        assertNull(CssModules.resolveModuleForBinding(tsx, "styles"))
    }

    // --- cssModuleBindings -------------------------------------------------

    fun testCssModuleBindingsMapsBindingToClassNames() {
        myFixture.addFileToProject("src/Comp.module.scss", ".a {} .b {}")
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            """
            import styles from './Comp.module.scss';
            import React from 'react';
            """.trimIndent(),
        )
        val bindings = CssModules.cssModuleBindings(tsx)
        assertEquals(setOf("styles"), bindings.keys)
        assertEquals(setOf("a", "b"), bindings["styles"])
    }

    fun testCssModuleBindingsEmptyWhenNoModuleImport() {
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            "import React from 'react';\nimport foo from './foo.ts';",
        )
        assertTrue(CssModules.cssModuleBindings(tsx).isEmpty())
    }

    fun testCssModuleBindingsHandlesMultipleModules() {
        myFixture.addFileToProject("src/A.module.scss", ".a1 {}")
        myFixture.addFileToProject("src/B.module.css", ".b1 {} .b2 {}")
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            """
            import s from './A.module.scss';
            import t from './B.module.css';
            """.trimIndent(),
        )
        val bindings = CssModules.cssModuleBindings(tsx)
        assertEquals(setOf("s", "t"), bindings.keys)
        assertEquals(setOf("a1"), bindings["s"])
        assertEquals(setOf("b1", "b2"), bindings["t"])
    }

    // --- findImporters -----------------------------------------------------

    fun testFindImportersFindsImportingFileAndBinding() {
        val scss = myFixture.addFileToProject("src/Comp.module.scss", ".a {}")
        myFixture.addFileToProject(
            "src/Comp.tsx",
            "import styles from './Comp.module.scss';\nconst x = styles.a;",
        )
        val importers = CssModules.findImporters(scss)
        assertEquals(1, importers.size)
        val (file, bindings) = importers.entries.first()
        assertEquals("Comp.tsx", file.name)
        assertEquals(setOf("styles"), bindings)
    }

    fun testFindImportersEmptyWhenNobodyImports() {
        val scss = myFixture.addFileToProject("src/Lonely.module.scss", ".a {}")
        assertTrue(CssModules.findImporters(scss).isEmpty())
    }

    // --- collectUsedClassNames --------------------------------------------

    fun testCollectUsedClassNamesReportsOnlyReferenced() {
        val scss = myFixture.addFileToProject("src/Comp.module.scss", ".used {} .unused {}")
        myFixture.addFileToProject(
            "src/Comp.tsx",
            "import styles from './Comp.module.scss';\nconst x = styles.used;",
        )
        assertEquals(setOf("used"), CssModules.collectUsedClassNames(scss))
    }

    fun testCollectUsedClassNamesIgnoresOtherQualifiers() {
        val scss = myFixture.addFileToProject("src/Comp.module.scss", ".a {} .b {}")
        myFixture.addFileToProject(
            "src/Comp.tsx",
            """
            import styles from './Comp.module.scss';
            const other = { a: 1 };
            const x = other.a;
            const y = styles.b;
            """.trimIndent(),
        )
        // `other.a` must NOT count as a usage of class `a`; only `styles.b` does.
        assertEquals(setOf("b"), CssModules.collectUsedClassNames(scss))
    }
}
