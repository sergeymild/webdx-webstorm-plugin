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

    fun testClassUsedOnlyViaExtendIsNotUnused() {
        // common.commonButton is consumed solely through `@extend .commonButton` in Comp.module.scss
        // (Comp is used from JS, so the chain has a JS consumer and the inspection runs), but
        // .commonButton is never referenced as `styles.commonButton`. It must NOT be flagged unused.
        val common = myFixture.addFileToProject(
            "src/common.module.scss",
            ".commonButton { cursor: pointer; }\n.deadInCommon { color: green; }",
        )
        myFixture.addFileToProject(
            "src/Comp.module.scss",
            "@import './common.module.scss';\n.button { @extend .commonButton; }",
        )
        myFixture.addFileToProject(
            "src/Comp.tsx",
            "import styles from './Comp.module.scss';\nconst a = styles.button;",
        )
        myFixture.configureFromExistingVirtualFile(common.virtualFile)
        myFixture.enableInspections(CssModuleUnusedClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "'commonButton' is used via @extend and must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'commonButton'") },
        )
        assertTrue(
            "'deadInCommon' is never referenced and SHOULD be flagged, got: $descriptions",
            descriptions.any { it.contains("'deadInCommon'") && it.contains("not used") },
        )
    }

    fun testExtendReferenceSiteIsNotFlaggedUnused() {
        // The `.commonButton` inside `.button { @extend .commonButton }` is a REFERENCE, not a
        // declaration, so it must not be greyed as an unused module class of Comp.module.scss.
        myFixture.addFileToProject("src/common.module.scss", ".commonButton { cursor: pointer; }")
        val comp = myFixture.addFileToProject(
            "src/Comp.module.scss",
            "@import './common.module.scss';\n.button { @extend .commonButton; }",
        )
        myFixture.addFileToProject(
            "src/Comp.tsx",
            "import styles from './Comp.module.scss';\nconst a = styles.button;",
        )
        myFixture.configureFromExistingVirtualFile(comp.virtualFile)
        myFixture.enableInspections(CssModuleUnusedClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "@extend .commonButton reference must NOT be flagged unused, got: $descriptions",
            descriptions.any { it.contains("'commonButton'") },
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

    fun testUnusedBamClassIsReported() {
        myFixture.addFileToProject(
            "src/Use.tsx",
            "import styles from './Bam.module.scss';\nconst x = styles.sidebar__search;",
        )
        val scss = myFixture.addFileToProject(
            "src/Bam.module.scss",
            "\$sidebar: '.sidebar';\n#{\$sidebar} {\n" +
                "  &__search { display: none; }\n" +
                "  &__dead { display: none; }\n}",
        )
        myFixture.configureFromExistingVirtualFile(scss.virtualFile)
        myFixture.enableInspections(CssModuleUnusedClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "expected 'sidebar__dead' unused, got: $descriptions",
            descriptions.any { it.contains("'sidebar__dead'") && it.contains("not used") },
        )
        assertFalse(
            "'sidebar__search' is used and must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'sidebar__search'") },
        )
    }

    fun testAllDeclarationSitesOfUnusedBamClassAreFlagged() {
        // A bam class declared at MULTIPLE selector sites (the `#{$sidebar}__x` form inside
        // `&--expanded` AND the top-level `&__x`) must be greyed at EVERY site when unused,
        // matching how a literal `.foo` declared twice is flagged at both occurrences.
        myFixture.addFileToProject(
            "src/Use.tsx",
            "import styles from './Bam.module.scss';\nconst x = styles.sidebar__content;",
        )
        val scss = myFixture.addFileToProject(
            "src/Bam.module.scss",
            "\$sidebar: '.sidebar';\n#{\$sidebar} {\n" +
                "  &--expanded { #{\$sidebar}__search { display: block; } }\n" +
                "  &__search { display: none; }\n" + // second declaration site of sidebar__search
                "  &__content { width: 100%; }\n" +
                "}",
        )
        myFixture.configureFromExistingVirtualFile(scss.virtualFile)
        myFixture.enableInspections(CssModuleUnusedClassInspection())

        val flaggedSearch = myFixture.doHighlighting().filter {
            it.description?.contains("'sidebar__search'") == true && it.description!!.contains("not used")
        }
        assertEquals(
            "both sidebar__search declaration sites must be greyed, got: ${flaggedSearch.map { it.text }}",
            2,
            flaggedSearch.size,
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

    // --- bracket access: styles['class--mod'] -----------------------------

    fun testBracketAccessedBamClassIsNotUnused() {
        // `sidebar--expanded` (a `--` modifier) can only be referenced via bracket access
        // `styles['sidebar--expanded']`; it must count as used and NOT be greyed.
        myFixture.addFileToProject(
            "src/Use.tsx",
            "import styles from './Bam.module.scss';\nconst x = styles['sidebar--expanded'];",
        )
        val scss = myFixture.addFileToProject(
            "src/Bam.module.scss",
            "\$sidebar: '.sidebar';\n#{\$sidebar} {\n  &--expanded { color: red; }\n  &--dead { color: blue; }\n}",
        )
        myFixture.configureFromExistingVirtualFile(scss.virtualFile)
        myFixture.enableInspections(CssModuleUnusedClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "bracket-used 'sidebar--expanded' must NOT be unused, got: $descriptions",
            descriptions.any { it.contains("'sidebar--expanded'") },
        )
        assertTrue(
            "'sidebar--dead' should be unused, got: $descriptions",
            descriptions.any { it.contains("'sidebar--dead'") && it.contains("not used") },
        )
    }

    fun testDynamicBracketAccessSuppressesUnused() {
        // `styles[variant]` is a computed/dynamic key — we can't tell which classes it hits,
        // so NO class of that module may be flagged unused (mirrors the real ComparisonCard
        // case where `.neutral`/`.accent` are reached only via `styles[variant]`).
        myFixture.addFileToProject(
            "src/Card.tsx",
            "import styles from './Card.module.scss';\n" +
                "function Card({ variant }) {\n" +
                "  return <div className={[styles.card, styles[variant]].join(' ')} />;\n" +
                "}",
        )
        val scss = myFixture.addFileToProject(
            "src/Card.module.scss",
            ".card { color: black; }\n.neutral { color: grey; }\n.accent { color: blue; }",
        )
        myFixture.configureFromExistingVirtualFile(scss.virtualFile)
        myFixture.enableInspections(CssModuleUnusedClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "dynamic styles[variant] access must suppress ALL unused flags, got: $descriptions",
            descriptions.any { it.contains("not used") },
        )
    }

    fun testBracketUnknownClassIsFlagged() {
        myFixture.addFileToProject(
            "Bam.module.scss",
            "\$sidebar: '.sidebar';\n#{\$sidebar} {\n  &--expanded { color: red; }\n}",
        )
        myFixture.configureByText(
            "Use.tsx",
            "import styles from './Bam.module.scss';\n" +
                "const a = styles['sidebar--expanded'];\n" +
                "const b = styles['nope--missing'];",
        )
        myFixture.enableInspections(CssModuleUnknownClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "typo bracket class 'nope--missing' should be flagged, got: $descriptions",
            descriptions.any { it.contains("Unknown CSS module class 'nope--missing'") },
        )
        assertFalse(
            "valid bracket class 'sidebar--expanded' must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'sidebar--expanded'") },
        )
    }
}
