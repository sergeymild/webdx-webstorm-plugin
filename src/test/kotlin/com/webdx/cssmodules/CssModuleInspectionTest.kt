package com.webdx.cssmodules

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

    // --- Override of an imported class (CSS side) -------------------------

    fun testOverrideOfImportedClassIsFlagged() {
        myFixture.addFileToProject(
            "src/common.module.scss",
            ".nextButton { color: red; }\n.note { color: green; }",
        )
        val scss = myFixture.addFileToProject(
            "src/Comp.module.scss",
            "@import './common.module.scss';\n.nextButton { color: blue; }\n.local { color: black; }",
        )
        myFixture.configureFromExistingVirtualFile(scss.virtualFile)
        myFixture.enableInspections(CssModuleOverrideClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "expected 'nextButton' flagged as overriding common.module.scss, got: $descriptions",
            descriptions.any { it.contains("'nextButton'") && it.contains("overrides") && it.contains("common.module.scss") },
        )
        assertFalse(
            "'local' is not imported and must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'local'") },
        )
    }

    fun testNoOverrideFlaggedWithoutImports() {
        val scss = myFixture.addFileToProject(
            "src/Solo.module.scss",
            ".a { color: red; }\n.b { color: blue; }",
        )
        myFixture.configureFromExistingVirtualFile(scss.virtualFile)
        myFixture.enableInspections(CssModuleOverrideClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "nothing should be flagged as an override, got: $descriptions",
            descriptions.any { it.contains("overrides") },
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

    fun testImportedClassUsedViaConsumerIsNotUnused() {
        // common is @import-ed into Comp.module.scss (CSS-to-CSS), not imported directly by JS;
        // used as styles.shared in Comp.tsx through that chain.
        val common = myFixture.addFileToProject(
            "src/common.module.scss",
            ".shared { color: red; }\n.deadInCommon { color: green; }",
        )
        myFixture.addFileToProject(
            "src/Comp.module.scss",
            "@import './common.module.scss';\n.local { color: blue; }",
        )
        myFixture.addFileToProject(
            "src/Comp.tsx",
            "import styles from './Comp.module.scss';\nconst a = styles.shared;\nconst b = styles.local;",
        )
        myFixture.configureFromExistingVirtualFile(common.virtualFile)
        myFixture.enableInspections(CssModuleUnusedClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "'shared' is used via a consumer and must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'shared'") },
        )
        assertTrue(
            "'deadInCommon' is never referenced and SHOULD be flagged, got: $descriptions",
            descriptions.any { it.contains("'deadInCommon'") && it.contains("not used") },
        )
    }

    fun testBamClassNotFlaggedAsUnknown() {
        myFixture.addFileToProject(
            "src/Bam.module.scss",
            "\$sidebar: '.sidebar';\n#{\$sidebar} {\n  &__search { display: none; }\n}",
        )
        val tsx = myFixture.addFileToProject(
            "src/Use.tsx",
            "import styles from './Bam.module.scss';\nconst x = styles.sidebar__search;",
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(CssModuleUnknownClassInspection())
        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "bam class must not be flagged unknown, got: $descriptions",
            descriptions.any { it.contains("Unknown CSS module class") },
        )
    }

    fun testImportedClassIsNotFlaggedAsUnknown() {
        myFixture.addFileToProject("src/common.module.scss", ".nextButton { color: red; }")
        myFixture.addFileToProject(
            "src/Comp.module.scss",
            "@import './common.module.scss';\n.local { color: blue; }",
        )
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            """
            import styles from './Comp.module.scss';
            const a = styles.nextButton;
            const b = styles.local;
            const c = styles.doesNotExist;
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(CssModuleUnknownClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "imported class 'nextButton' must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'nextButton'") },
        )
        assertTrue(
            "expected 'doesNotExist' still flagged, got: $descriptions",
            descriptions.any { it.contains("Unknown CSS module class 'doesNotExist'") },
        )
    }
}
