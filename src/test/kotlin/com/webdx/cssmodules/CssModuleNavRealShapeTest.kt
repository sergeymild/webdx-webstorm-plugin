package com.webdx.cssmodules

import com.intellij.psi.css.CssClass
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Reproduces the real CheckboxesPageContainer shape that returned null in the IDE:
 * relative import of styles.module.scss, which has its own .nextButton AND imports
 * common via the at-alias, used inside a JSX className. resolveTarget must return
 * the local override.
 */
class CssModuleNavRealShapeTest : BasePlatformTestCase() {

    private fun setUpChain() {
        myFixture.addFileToProject(
            "tsconfig.json",
            """{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""",
        )
        myFixture.addFileToProject(
            "src/containers/Onboarding/common.module.scss",
            ".nextButton { width: 327px; }\n.note { }",
        )
        myFixture.addFileToProject(
            "styles.module.scss",
            "@import \"@/src/containers/Onboarding/common.module.scss\";\n.container { }\n.nextButton { color: blue; }",
        )
    }

    private fun resolvedFileNameAtCaret(): String? {
        val element = myFixture.file.findElementAt(myFixture.caretOffset) ?: return null
        val target = CssModuleClassNavigation.resolveTarget(element) ?: return null
        assertTrue("target is a CssClass", target is CssClass)
        return target.containingFile?.name
    }

    fun testPlainMemberAccess() {
        setUpChain()
        myFixture.configureByText(
            "CheckboxesPageContainer.tsx",
            "import styles from './styles.module.scss';\nconst x = styles.next<caret>Button;",
        )
        assertEquals("styles.module.scss", resolvedFileNameAtCaret())
    }

    fun testJsxClassNameAccess() {
        setUpChain()
        myFixture.configureByText(
            "CheckboxesPageContainer.tsx",
            "import styles from './styles.module.scss';\nconst C = () => <div className={styles.next<caret>Button} />;",
        )
        assertEquals("styles.module.scss", resolvedFileNameAtCaret())
    }
}
