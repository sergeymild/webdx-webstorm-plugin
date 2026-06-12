package com.webdx.cssmodules

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests the Alt+Enter "Add @import for SCSS symbol" intention: when a `@include`/
 * function/variable/placeholder resolves only by name, it offers to add the
 * `@import` of the defining file, using the `@/` alias.
 */
class CssModuleImportSymbolIntentionTest : BasePlatformTestCase() {

    private val intentionText = "Add @import for this SCSS symbol"

    private fun tsconfig() = myFixture.addFileToProject(
        "tsconfig.json",
        """{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""",
    )

    fun testOffersAliasImportForMixin() {
        tsconfig()
        myFixture.addFileToProject("styles/mixins.scss", "@mixin remove-scrollbar { overflow: hidden; }")
        myFixture.configureByText(
            "Comp.module.scss",
            ".a {\n  @include remove-scroll<caret>bar();\n}",
        )
        val intention = myFixture.findSingleIntention(intentionText)
        myFixture.launchAction(intention)
        assertTrue(
            "expected the alias @import inserted, got:\n${myFixture.editor.document.text}",
            myFixture.editor.document.text.contains("@import '@/styles/mixins.scss';"),
        )
    }

    fun testOffersImportForVariable() {
        tsconfig()
        myFixture.addFileToProject("styles/vars.scss", "\$brand-color: #fff;")
        myFixture.configureByText(
            "Comp.module.scss",
            ".a {\n  color: \$brand-co<caret>lor;\n}",
        )
        val intention = myFixture.findSingleIntention(intentionText)
        myFixture.launchAction(intention)
        assertTrue(myFixture.editor.document.text.contains("@import '@/styles/vars.scss';"))
    }

    fun testNotOfferedWhenAlreadyImported() {
        tsconfig()
        myFixture.addFileToProject("styles/mixins.scss", "@mixin remove-scrollbar { overflow: hidden; }")
        myFixture.configureByText(
            "Comp.module.scss",
            "@import '@/styles/mixins.scss';\n.a {\n  @include remove-scroll<caret>bar();\n}",
        )
        assertEmpty(myFixture.filterAvailableIntentions(intentionText))
    }

    fun testNotOfferedForUnknownSymbol() {
        tsconfig()
        myFixture.addFileToProject("styles/mixins.scss", "@mixin remove-scrollbar { overflow: hidden; }")
        myFixture.configureByText(
            "Comp.module.scss",
            ".a {\n  @include totally-unkno<caret>wn();\n}",
        )
        assertEmpty(myFixture.filterAvailableIntentions(intentionText))
    }
}
