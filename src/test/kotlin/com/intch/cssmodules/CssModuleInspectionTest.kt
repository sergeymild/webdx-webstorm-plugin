package com.intch.cssmodules

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Functional tests for the two inspections, driven through the real highlighting
 * pipeline (enableInspections + doHighlighting) on an in-memory fixture.
 */
class CssModuleInspectionTest : BasePlatformTestCase() {

    // --- Unused CSS module class (CSS side) -------------------------------

    fun testUnusedClassIsReported() {
        myFixture.addFileToProject(
            "src/Comp.tsx",
            "import styles from './Comp.module.scss';\nconst x = styles.used;",
        )
        val scss = myFixture.addFileToProject(
            "src/Comp.module.scss",
            ".used { color: red; }\n.unused { color: blue; }",
        )
        myFixture.configureFromExistingVirtualFile(scss.virtualFile)
        myFixture.enableInspections(CssModuleUnusedClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "expected an 'unused' warning, got: $descriptions",
            descriptions.any { it.contains("'unused'") && it.contains("not used") },
        )
        assertFalse(
            "'used' must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'used'") },
        )
    }

    fun testNoUnusedReportedWhenNoImporters() {
        // Without any importing file the inspection bails out (can't tell what's used).
        val scss = myFixture.addFileToProject(
            "src/Lonely.module.scss",
            ".whatever { color: red; }",
        )
        myFixture.configureFromExistingVirtualFile(scss.virtualFile)
        myFixture.enableInspections(CssModuleUnusedClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "nothing should be flagged without importers, got: $descriptions",
            descriptions.any { it.contains("not used") },
        )
    }

    // --- Unknown CSS module class (JS/TS side) ----------------------------

    fun testUnknownClassIsReported() {
        myFixture.addFileToProject(
            "src/Comp.module.scss",
            ".container { color: red; }",
        )
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            """
            import styles from './Comp.module.scss';
            const a = styles.container;
            const b = styles.doesNotExist;
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(CssModuleUnknownClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "expected 'doesNotExist' to be flagged, got: $descriptions",
            descriptions.any { it.contains("Unknown CSS module class 'doesNotExist'") },
        )
        assertFalse(
            "the real class 'container' must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'container'") },
        )
    }

    fun testKnownClassesAreNotReported() {
        myFixture.addFileToProject(
            "src/Comp.module.scss",
            ".a { color: red; }\n.b { color: blue; }",
        )
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            """
            import styles from './Comp.module.scss';
            const x = styles.a;
            const y = styles.b;
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(CssModuleUnknownClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "no unknown-class warning expected, got: $descriptions",
            descriptions.any { it.contains("Unknown CSS module class") },
        )
    }
}
