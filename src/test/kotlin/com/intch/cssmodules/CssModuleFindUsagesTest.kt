package com.intch.cssmodules

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
}
