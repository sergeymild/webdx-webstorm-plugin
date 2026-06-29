package com.webdx.cssmodules

import com.intellij.psi.css.CssSimpleSelector
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BamSelectorsTest : BasePlatformTestCase() {

    private val bam = """
        ${'$'}sidebar: '.sidebar';

        #{${'$'}sidebar} {
          display: flex;

          &--expanded {
            #{${'$'}sidebar}__content { padding: 0; }
          }

          &__search { display: none; }
          &__mobile-toggle { color: red; }

          &:after { content: ''; }
        }
    """.trimIndent()

    fun testCollectsSubjectClassNames() {
        val scss = myFixture.addFileToProject("src/Bam.module.scss", bam)
        val names = BamSelectors.bamClassDeclarations(scss).keys
        assertEquals(
            setOf("sidebar", "sidebar--expanded", "sidebar__content", "sidebar__search", "sidebar__mobile-toggle"),
            names,
        )
    }

    fun testDeclarationElementIsTheSelector() {
        val scss = myFixture.addFileToProject("src/Bam.module.scss", bam)
        val decl = BamSelectors.bamClassDeclarations(scss)["sidebar__search"]?.single()
        assertTrue("expected a CssSimpleSelector", decl is CssSimpleSelector)
        assertEquals("&__search", decl!!.text)
    }

    fun testLiteralParentBemIsResolved() {
        // No variable — plain `.foo { &__bar {} }`. `.foo` is a real CssClass, but
        // `&__bar` has no CssClass; the resolver still yields `foo__bar`.
        val scss = myFixture.addFileToProject(
            "src/Plain.module.scss",
            ".foo {\n  &__bar { color: red; }\n}",
        )
        assertEquals(setOf("foo__bar"), BamSelectors.bamClassDeclarations(scss).keys)
    }

    fun testUnknownVariableYieldsNothing() {
        val scss = myFixture.addFileToProject(
            "src/Unknown.module.scss",
            "#{${'$'}missing} {\n  &__x { color: red; }\n}",
        )
        assertTrue(BamSelectors.bamClassDeclarations(scss).isEmpty())
    }

    // --- cross-file imported variables -------------------------------------

    fun testResolvesImportedBareVar() {
        myFixture.addFileToProject("src/vars.scss", "${'$'}sidebar: '.sidebar';")
        val scss = myFixture.addFileToProject(
            "src/Bam.module.scss",
            "@import './vars.scss';\n#{${'$'}sidebar} {\n  &__search { display: none; }\n}",
        )
        assertEquals(setOf("sidebar", "sidebar__search"), BamSelectors.bamClassDeclarations(scss).keys)
    }

    fun testResolvesAliasedUseVar() {
        myFixture.addFileToProject("src/vars.scss", "${'$'}sidebar: '.sidebar';")
        val scss = myFixture.addFileToProject(
            "src/Bam.module.scss",
            "@use './vars.scss' as v;\n#{v.${'$'}sidebar} {\n  &__search { display: none; }\n}",
        )
        assertEquals(setOf("sidebar", "sidebar__search"), BamSelectors.bamClassDeclarations(scss).keys)
    }

    fun testResolvesDefaultNamespaceUseVar() {
        myFixture.addFileToProject("src/vars.scss", "${'$'}sidebar: '.sidebar';")
        val scss = myFixture.addFileToProject(
            "src/Bam.module.scss",
            "@use './vars.scss';\n#{vars.${'$'}sidebar} {\n  &__search { display: none; }\n}",
        )
        assertEquals(setOf("sidebar", "sidebar__search"), BamSelectors.bamClassDeclarations(scss).keys)
    }

    // --- real-example shapes: @include containers + literal-parent BEM ------

    fun testResolvesBamInsideIncludeContainer() {
        // `&__search` and `#{$sidebar}__content` nested inside an @include content block
        // (the `sidebar.component.scss` shape). `&` must resolve through the container.
        val scss = myFixture.addFileToProject(
            "src/Sidebar.module.scss",
            "${'$'}sidebar: '.sidebar';\n#{${'$'}sidebar} {\n" +
                "  @include breakpoints.breakpoint-min(laptop) {\n" +
                "    &__search { display: block; }\n" +
                "    #{${'$'}sidebar}__content { --content-height: 90vh; }\n" +
                "  }\n}",
        )
        val names = BamSelectors.bamClassDeclarations(scss).keys
        assertTrue("&__search inside @include -> sidebar__search, got $names", names.contains("sidebar__search"))
        assertTrue("#{\$sidebar}__content inside @include -> sidebar__content, got $names", names.contains("sidebar__content"))
        assertFalse("the @include container must not produce a class, got $names",
            names.any { it.startsWith("breakpoint") })
    }

    fun testLiteralParentBemModifierAndNotArg() {
        // `input-v2.component.scss` shape: literal block class with a `&--mod`, and a
        // `:not(.x)` whose argument must NOT be treated as a declaration.
        val scss = myFixture.addFileToProject(
            "src/Input.module.scss",
            ".input__label {\n" +
                "  &--focused { box-shadow: 0 0 0 4px red; }\n" +
                "  &:not(.input__label--disabled) { color: red; }\n" +
                "}",
        )
        val names = BamSelectors.bamClassDeclarations(scss).keys
        assertTrue("expected input__label--focused, got $names", names.contains("input__label--focused"))
        assertFalse("`:not(.x)` arg must not be a declaration, got $names", names.contains("input__label--disabled"))
    }

    // --- Task 2: bamClassForElement -------------------------------------------

    fun testBamClassForElementResolvesCaretSelector() {
        val scss = myFixture.addFileToProject("src/Bam.module.scss", bam)
        myFixture.configureFromExistingVirtualFile(scss.virtualFile)
        // Place a caret on the `&__search` selector text.
        val offset = scss.text.indexOf("&__search") + 3 // inside "search"
        val element = scss.findElementAt(offset)!!
        assertEquals("sidebar__search", BamSelectors.bamClassForElement(element))
    }

    fun testBamClassForElementNullOutsideBam() {
        val scss = myFixture.addFileToProject("src/Plain.module.scss", ".foo { color: red; }")
        val offset = scss.text.indexOf("color")
        val element = scss.findElementAt(offset)!!
        assertNull(BamSelectors.bamClassForElement(element))
    }

    fun testCamelCaseAmpersandConcatIsResolved() {
        // `.arrow { &Prev { &Icon {} } }` — `&` concatenation WITHOUT a `__`/`--`
        // separator. Resolves to `.arrowPrev` / `.arrowPrevIcon`.
        val scss = myFixture.addFileToProject(
            "src/Arrow.module.scss",
            ".arrow {\n  &Prev {\n    &Icon { margin-left: 12px; }\n  }\n}",
        )
        assertEquals(
            setOf("arrowPrev", "arrowPrevIcon"),
            BamSelectors.bamClassDeclarations(scss).keys,
        )
    }

    fun testBamClassForCamelCaseAmpersandCaret() {
        val scss = myFixture.addFileToProject(
            "src/Arrow.module.scss",
            ".arrow {\n  &Prev {\n    &Icon { margin-left: 12px; }\n  }\n}",
        )
        val offset = scss.text.indexOf("&Prev") + 2 // inside "Prev"
        val element = scss.findElementAt(offset)!!
        assertEquals("arrowPrev", BamSelectors.bamClassForElement(element))
    }

    fun testCommaGroupedBemMembersAreAllCollected() {
        // `cards-with-filters-v2.component.scss` shape: a `&`-comma group declares
        // SEVERAL classes. Every member must be collected — a class declared only as a
        // non-first member (here `sidebar__emptyState`) otherwise goes missing, which
        // would falsely redline `styles.emptyState` as an unknown class.
        val scss = myFixture.addFileToProject(
            "src/Cards.module.scss",
            "${'$'}sidebar: '.sidebar';\n#{${'$'}sidebar} {\n" +
                "  &__cards-with-btn,\n  &__emptyState { width: 100%; }\n" +
                "  &__a, &__b, &__c { display: none; }\n" +
                "}",
        )
        assertEquals(
            setOf("sidebar", "sidebar__cards-with-btn", "sidebar__emptyState",
                "sidebar__a", "sidebar__b", "sidebar__c"),
            BamSelectors.bamClassDeclarations(scss).keys,
        )
    }

    fun testCommaGroupSubjectSelectorPointsAtTheRightMember() {
        val scss = myFixture.addFileToProject(
            "src/Cards.module.scss",
            "${'$'}sidebar: '.sidebar';\n#{${'$'}sidebar} {\n" +
                "  &__cards-with-btn,\n  &__emptyState { width: 100%; }\n}",
        )
        val decl = BamSelectors.bamClassDeclarations(scss)["sidebar__emptyState"]?.single()
        assertTrue("expected a CssSimpleSelector", decl is CssSimpleSelector)
        assertEquals("&__emptyState", decl!!.text)
    }

    fun testDescendantSelectorIsIgnored() {
        val scss = myFixture.addFileToProject(
            "src/Desc.module.scss",
            "${'$'}sidebar: '.sidebar';\n#{${'$'}sidebar} {\n  & .child { color: red; }\n}",
        )
        assertFalse("descendant `.child` must not be a bam class",
            BamSelectors.bamClassDeclarations(scss).containsKey("child"))
        // the block itself still resolves
        assertTrue(BamSelectors.bamClassDeclarations(scss).containsKey("sidebar"))
    }
}
