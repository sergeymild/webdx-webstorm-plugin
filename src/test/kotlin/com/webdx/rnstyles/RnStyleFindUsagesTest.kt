package com.webdx.rnstyles

import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Scoped Find Usages on a `StyleSheet.create` key. */
class RnStyleFindUsagesTest : BasePlatformTestCase() {

    private fun keyPropAtCaret(): JSProperty {
        val el = myFixture.file.findElementAt(myFixture.caretOffset)
        return PsiTreeUtil.getParentOfType(el, JSProperty::class.java, false) ?: error("no JSProperty at caret")
    }

    fun testReportsOnlyAccessesOfThatKeyInline() {
        myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ fo<caret>o: { flex: 1 }, bar: { flex: 1 } })\n" +
                "const a = styles.foo\nconst b = styles.foo\nconst c = styles.bar",
        )
        val usages = myFixture.findUsages(keyPropAtCaret())
        assertEquals("usages: ${usages.map { it.element?.text }}", 2, usages.size)
    }

    fun testDoesNotMatchSameNamedAccessOnOtherQualifier() {
        myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ fo<caret>o: { flex: 1 } })\n" +
                "const other = { foo: 1 }\nconst a = other.foo\nconst b = styles.foo",
        )
        val usages = myFixture.findUsages(keyPropAtCaret())
        assertEquals("usages: ${usages.map { it.element?.text }}", 1, usages.size)
    }

    fun testExportedKeyScopedToImporters() {
        myFixture.addFileToProject(
            "A.tsx",
            "import { styles } from './styles'\nconst x = styles.foo",
        )
        // B imports a DIFFERENT module but also writes styles.foo — must be excluded.
        myFixture.addFileToProject(
            "B.tsx",
            "import { styles } from './other'\nconst y = styles.foo",
        )
        myFixture.addFileToProject(
            "other.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ foo: { flex: 1 } })",
        )
        myFixture.configureByText(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ fo<caret>o: { flex: 1 } })",
        )
        val usages = myFixture.findUsages(keyPropAtCaret())
        assertEquals("usages: ${usages.map { it.element?.containingFile?.name }}", 1, usages.size)
        assertEquals("A.tsx", usages.first().element?.containingFile?.name)
    }
}
