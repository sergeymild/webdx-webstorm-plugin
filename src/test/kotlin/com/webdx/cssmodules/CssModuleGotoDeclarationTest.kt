package com.webdx.cssmodules

import com.intellij.psi.css.CssClass
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for [CssModuleGotoDeclarationHandler]: `styles.<class>` resolves to the
 * single effective declaration — the local override when the importing module
 * redefines an `@import`-ed class, otherwise the declaring file in the chain.
 */
class CssModuleGotoDeclarationTest : BasePlatformTestCase() {

    private val handler = CssModuleGotoDeclarationHandler()

    private fun targetsAtCaret(): Array<com.intellij.psi.PsiElement>? {
        val element = myFixture.file.findElementAt(myFixture.caretOffset)
        return handler.getGotoDeclarationTargets(element, myFixture.caretOffset, myFixture.editor)
    }

    fun testGoesToLocalOverrideNotImported() {
        myFixture.addFileToProject("common.module.scss", ".nextButton { width: 327px; }")
        myFixture.addFileToProject(
            "Comp.module.scss",
            "@import './common.module.scss';\n.nextButton { color: blue; }\n.local { }",
        )
        myFixture.configureByText(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nconst x = styles.next<caret>Button;",
        )

        val targets = targetsAtCaret()
        assertNotNull("expected a target", targets)
        assertEquals("usages: ${targets?.map { it.containingFile?.name }}", 1, targets!!.size)
        val target = targets[0]
        assertTrue("target is a CssClass", target is CssClass)
        // The local override wins the cascade -> navigation lands on the importing module.
        assertEquals("Comp.module.scss", target.containingFile?.name)
    }

    fun testGoesToImportedDeclarationWhenNotOverridden() {
        myFixture.addFileToProject("common.module.scss", ".note { color: red; }")
        myFixture.addFileToProject(
            "Comp.module.scss",
            "@import './common.module.scss';\n.local { }",
        )
        myFixture.configureByText(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nconst x = styles.no<caret>te;",
        )

        val targets = targetsAtCaret()
        assertNotNull("expected a target", targets)
        assertEquals(1, targets!!.size)
        assertEquals("common.module.scss", targets[0].containingFile?.name)
    }

    fun testReturnsNullForUnrelatedQualifier() {
        myFixture.addFileToProject("Comp.module.scss", ".local { }")
        myFixture.configureByText(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nconst obj = { foo: 1 };\nconst x = obj.fo<caret>o;",
        )
        assertNull("non-module qualifier must not resolve", targetsAtCaret())
    }

    fun testReturnsNullForUnknownClass() {
        myFixture.addFileToProject("Comp.module.scss", ".local { }")
        myFixture.configureByText(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nconst x = styles.doesNot<caret>Exist;",
        )
        assertNull("class not declared anywhere must not resolve", targetsAtCaret())
    }

    fun testGoesToBamSelector() {
        myFixture.addFileToProject(
            "Bam.module.scss",
            "\$sidebar: '.sidebar';\n#{\$sidebar} {\n  &__search { display: none; }\n}",
        )
        myFixture.configureByText(
            "Use.tsx",
            "import styles from './Bam.module.scss';\nconst x = styles.sidebar__sea<caret>rch;",
        )
        val targets = targetsAtCaret()
        assertNotNull("expected a target", targets)
        assertEquals(1, targets!!.size)
        assertEquals("Bam.module.scss", targets[0].containingFile?.name)
        assertEquals("&__search", targets[0].text)
    }

    fun testGoesToBamSelectorViaBracketAccess() {
        // `--`-modifier classes are referenced as `styles['sidebar--expanded']`; Cmd+Click
        // on the string literal must navigate to the `&--expanded` selector.
        myFixture.addFileToProject(
            "Bam.module.scss",
            "\$sidebar: '.sidebar';\n#{\$sidebar} {\n  &--expanded { color: red; }\n}",
        )
        myFixture.configureByText(
            "Use.tsx",
            "import styles from './Bam.module.scss';\nconst x = styles['sidebar--exp<caret>anded'];",
        )
        val targets = targetsAtCaret()
        assertNotNull("expected a target", targets)
        assertEquals(1, targets!!.size)
        assertEquals("Bam.module.scss", targets[0].containingFile?.name)
        assertEquals("&--expanded", targets[0].text)
    }
}
