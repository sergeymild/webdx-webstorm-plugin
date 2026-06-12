package com.webdx.cssmodules

import com.intellij.psi.css.CssClass
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for [CssModuleDirectNavigationProvider]: `styles.<class>` resolves to the
 * single effective declaration. This is the EP the modern Ctrl+Click path consults
 * first, short-circuiting the TS service's multi-target symbol navigation.
 */
class CssModuleDirectNavigationTest : BasePlatformTestCase() {

    private val provider = CssModuleDirectNavigationProvider()

    private fun navTargetAtCaret(): com.intellij.psi.PsiElement? {
        val element = myFixture.file.findElementAt(myFixture.caretOffset) ?: return null
        return provider.getNavigationElement(element)
    }

    fun testNavigatesToLocalOverrideNotImported() {
        myFixture.addFileToProject("common.module.scss", ".nextButton { width: 327px; }")
        myFixture.addFileToProject(
            "Comp.module.scss",
            "@import './common.module.scss';\n.nextButton { color: blue; }\n.local { }",
        )
        myFixture.configureByText(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nconst x = styles.next<caret>Button;",
        )

        val target = navTargetAtCaret()
        assertNotNull("expected a single navigation target", target)
        assertTrue("target is a CssClass", target is CssClass)
        assertEquals("Comp.module.scss", target!!.containingFile?.name)
    }

    fun testNavigatesToImportedWhenNotOverridden() {
        myFixture.addFileToProject("common.module.scss", ".note { color: red; }")
        myFixture.addFileToProject(
            "Comp.module.scss",
            "@import './common.module.scss';\n.local { }",
        )
        myFixture.configureByText(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nconst x = styles.no<caret>te;",
        )

        val target = navTargetAtCaret()
        assertNotNull(target)
        assertEquals("common.module.scss", target!!.containingFile?.name)
    }

    fun testReturnsNullForUnrelatedQualifier() {
        myFixture.addFileToProject("Comp.module.scss", ".local { }")
        myFixture.configureByText(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nconst obj = { foo: 1 };\nconst x = obj.fo<caret>o;",
        )
        assertNull(navTargetAtCaret())
    }
}
