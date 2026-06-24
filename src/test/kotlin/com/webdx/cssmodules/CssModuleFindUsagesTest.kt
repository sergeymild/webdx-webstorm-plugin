package com.webdx.cssmodules

import com.intellij.psi.css.CssClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Functional tests for scoped Find Usages on a CSS-module class selector.
 * The custom [CssModuleFindUsagesHandlerFactory] should report only the
 * `styles.<class>` accesses inside files that import that exact module.
 */
class CssModuleFindUsagesTest : BasePlatformTestCase() {

    private fun cssClassAtCaret(): CssClass {
        val el = myFixture.file.findElementAt(myFixture.caretOffset)
        return PsiTreeUtil.getParentOfType(el, CssClass::class.java, false)
            ?: error("no CssClass at caret")
    }

    fun testReportsOnlyAccessesOfThatClass() {
        myFixture.addFileToProject(
            "Comp.tsx",
            """
            import styles from './Comp.module.scss';
            const a = styles.foo;
            const b = styles.foo;
            const c = styles.bar;
            """.trimIndent(),
        )
        myFixture.configureByText("Comp.module.scss", ".fo<caret>o { color: red; }\n.bar { }")

        val usages = myFixture.findUsages(cssClassAtCaret())
        // Two `styles.foo`, not `styles.bar`.
        assertEquals("usages: ${usages.map { it.element?.text }}", 2, usages.size)
    }

    fun testDoesNotMatchSameNamedAccessOnOtherQualifier() {
        myFixture.addFileToProject(
            "Comp.tsx",
            """
            import styles from './Comp.module.scss';
            const other = { foo: 1 };
            const a = other.foo;
            const b = styles.foo;
            """.trimIndent(),
        )
        myFixture.configureByText("Comp.module.scss", ".fo<caret>o { color: red; }")

        val usages = myFixture.findUsages(cssClassAtCaret())
        // `other.foo` must be excluded; only `styles.foo` counts.
        assertEquals("usages: ${usages.map { it.element?.text }}", 1, usages.size)
    }

    fun testOnlyScansFilesImportingThisModule() {
        // A different component imports a DIFFERENT module but also uses `styles.foo`.
        myFixture.addFileToProject(
            "A.tsx",
            "import styles from './A.module.scss';\nconst x = styles.foo;",
        )
        myFixture.addFileToProject("A.module.scss", ".foo { }")
        myFixture.addFileToProject(
            "B.tsx",
            "import styles from './B.module.scss';\nconst y = styles.foo;",
        )
        myFixture.configureByText("B.module.scss", ".fo<caret>o { color: red; }")

        val usages = myFixture.findUsages(cssClassAtCaret())
        // Only B.tsx imports B.module.scss, so A.tsx's styles.foo must not appear.
        assertEquals("usages: ${usages.map { it.element?.containingFile?.name }}", 1, usages.size)
        assertEquals("B.tsx", usages.first().element?.containingFile?.name)
    }

    fun testFindsBamClassUsagesInImporters() {
        val scss = myFixture.addFileToProject(
            "Bam.module.scss",
            "\$sidebar: '.sidebar';\n#{\$sidebar} {\n  &__search { display: none; }\n}",
        )
        myFixture.addFileToProject(
            "Use.tsx",
            "import styles from './Bam.module.scss';\nconst a = styles.sidebar__search;\nconst b = styles.sidebar__search;",
        )
        // Place the target on the `&__search` selector leaf.
        val offset = scss.text.indexOf("&__search") + 3
        val element = scss.findElementAt(offset)!!
        val usages = myFixture.findUsages(element)
        assertEquals("usages: ${usages.map { it.element?.text }}", 2, usages.size)
    }

    fun testFindsCamelCaseAmpersandConcatUsages() {
        // `.arrow { &Prev { &Icon {} } }` — `&Prev` -> `.arrowPrev`, nested `&Icon` -> `.arrowPrevIcon`.
        // Cmd+Click on either selector must list its `styles.*` usages in the importer.
        val scss = myFixture.addFileToProject(
            "Arrow.module.scss",
            ".arrow {\n  &Prev {\n    &Icon { margin-left: 12px; }\n  }\n}",
        )
        myFixture.addFileToProject(
            "Use.tsx",
            "import styles from './Arrow.module.scss';\n" +
                "const a = styles.arrowPrev;\nconst b = styles.arrowPrevIcon;\nconst c = styles.arrowPrevIcon;",
        )
        val prevOffset = scss.text.indexOf("&Prev") + 2
        val prevUsages = myFixture.findUsages(scss.findElementAt(prevOffset)!!)
        assertEquals("arrowPrev usages: ${prevUsages.map { it.element?.text }}", 1, prevUsages.size)

        val iconOffset = scss.text.indexOf("&Icon") + 2
        val iconUsages = myFixture.findUsages(scss.findElementAt(iconOffset)!!)
        assertEquals("arrowPrevIcon usages: ${iconUsages.map { it.element?.text }}", 2, iconUsages.size)
    }

    fun testFindsBamClassUsagesViaBracketAccess() {
        val scss = myFixture.addFileToProject(
            "Bam.module.scss",
            "\$sidebar: '.sidebar';\n#{\$sidebar} {\n  &--expanded { color: red; }\n}",
        )
        myFixture.addFileToProject(
            "Use.tsx",
            "import styles from './Bam.module.scss';\n" +
                "const a = styles['sidebar--expanded'];\nconst b = styles['sidebar--expanded'];",
        )
        val offset = scss.text.indexOf("&--expanded") + 3
        val element = scss.findElementAt(offset)!!
        val usages = myFixture.findUsages(element)
        assertEquals("usages: ${usages.map { it.element?.text }}", 2, usages.size)
    }

    fun testReportsDynamicBracketAccessSite() {
        // `.neutral` is reachable only via the dynamic `styles[variant]` access — there is no
        // static `styles.neutral`. The dynamic access site must be reported as its usage so
        // Find Usages / Cmd+Click can navigate to where the class is applied via the variant.
        myFixture.addFileToProject(
            "Card.tsx",
            "import styles from './Card.module.scss';\n" +
                "function Card({ variant }) { return styles[variant]; }",
        )
        myFixture.configureByText(
            "Card.module.scss",
            ".card { }\n.neu<caret>tral { color: grey; }\n.accent { }",
        )

        val usages = myFixture.findUsages(cssClassAtCaret())
        assertEquals("usages: ${usages.map { it.element?.containingFile?.name + ":" + it.element?.text }}", 1, usages.size)
        assertEquals("Card.tsx", usages.first().element?.containingFile?.name)
    }

    fun testStaticallyUsedClassDoesNotIncludeDynamicSite() {
        // A class with a real static reference (`styles.bullets`) must report ONLY that — the
        // dynamic `styles[variant]` site in the same file must NOT be mixed in (the bug: every
        // class showed `styles[variant]` as an extra usage).
        myFixture.addFileToProject(
            "Card.tsx",
            "import styles from './Card.module.scss';\n" +
                "function Card({ variant }) {\n" +
                "  return [styles.card, styles[variant], styles.bullets];\n" +
                "}",
        )
        myFixture.configureByText(
            "Card.module.scss",
            ".card { }\n.neutral { }\n.accent { }\n.bul<caret>lets { }",
        )

        val usages = myFixture.findUsages(cssClassAtCaret())
        assertEquals("usages: ${usages.map { it.element?.text }}", 1, usages.size)
        assertEquals("bullets", usages.first().element?.text)
    }

    fun testDynamicSiteReportedOnlyForClassWithoutStaticUse() {
        // Same fixture: `.neutral` has NO static reference, so the dynamic `styles[variant]` site
        // IS its (only) usage — the fallback kicks in exactly for dynamic-only classes.
        myFixture.addFileToProject(
            "Card.tsx",
            "import styles from './Card.module.scss';\n" +
                "function Card({ variant }) {\n" +
                "  return [styles.card, styles[variant], styles.bullets];\n" +
                "}",
        )
        myFixture.configureByText(
            "Card.module.scss",
            ".card { }\n.neu<caret>tral { }\n.accent { }\n.bullets { }",
        )

        val usages = myFixture.findUsages(cssClassAtCaret())
        assertEquals("usages: ${usages.map { it.element?.text }}", 1, usages.size)
        assertEquals("styles", usages.first().element?.text)
    }

    fun testDoesNotReportDynamicSiteForOtherQualifier() {
        // A dynamic `other[variant]` on an unrelated object must NOT be reported as a usage.
        myFixture.addFileToProject(
            "Card.tsx",
            "import styles from './Card.module.scss';\n" +
                "const other = {};\nfunction Card({ variant }) { return other[variant]; }",
        )
        myFixture.configureByText("Card.module.scss", ".neu<caret>tral { color: grey; }")

        val usages = myFixture.findUsages(cssClassAtCaret())
        assertEquals("usages: ${usages.map { it.element?.text }}", 0, usages.size)
    }

    fun testReportsUsagesThroughAtImportChain() {
        // common is @import-ed into Comp.module.scss (CSS-to-CSS); Comp.tsx uses styles.shared
        // AND Comp.module.scss `@extend .shared`. Both kinds of usage must be reported.
        myFixture.addFileToProject(
            "Comp.module.scss",
            "@import './common.module.scss';\n.local { @extend .shared; }",
        )
        myFixture.addFileToProject(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nconst a = styles.shared;\nconst b = styles.shared;",
        )
        // An UNRELATED module that also declares .shared — must NOT be reported.
        myFixture.addFileToProject("Other.module.scss", ".shared { color: blue; }")

        myFixture.configureByText("common.module.scss", ".sha<caret>red { color: red; }")

        val usages = myFixture.findUsages(cssClassAtCaret())
        // Two `styles.shared` in Comp.tsx + the `@extend .shared` site in Comp.module.scss.
        // Other.module.scss's own `.shared` declaration must NOT appear.
        assertEquals(
            "usages: ${usages.map { it.element?.containingFile?.name + ":" + it.element?.text }}",
            3, usages.size,
        )
        assertEquals(
            "two styles.shared usages in Comp.tsx",
            2, usages.count { it.element?.containingFile?.name == "Comp.tsx" },
        )
        assertTrue(
            "the @extend .shared site in Comp.module.scss is reported",
            usages.any { it.element?.containingFile?.name == "Comp.module.scss" && it.element?.text == "shared" },
        )
        assertTrue(
            "Other.module.scss's own declaration must NOT be a usage",
            usages.none { it.element?.containingFile?.name == "Other.module.scss" },
        )
    }

    fun testReportsExtendClassAsUsageWithoutJsConsumer() {
        // `.commonButton` is consumed only by `@extend .commonButton` in an importing module —
        // no `styles.commonButton` anywhere. Find Usages on the declaration must still find it.
        myFixture.addFileToProject(
            "Comp.module.scss",
            "@import './common.module.scss';\n.button { @extend .commonButton; }",
        )
        myFixture.configureByText("common.module.scss", ".common<caret>Button { cursor: pointer; }")

        val usages = myFixture.findUsages(cssClassAtCaret())
        assertEquals(
            "usages: ${usages.map { it.element?.containingFile?.name + ":" + it.element?.text }}",
            1, usages.size,
        )
        assertEquals("Comp.module.scss", usages.first().element?.containingFile?.name)
        assertEquals("commonButton", usages.first().element?.text)
    }
}
