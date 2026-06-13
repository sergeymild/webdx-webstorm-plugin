package com.webdx.rnstyles

import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * `RnStyles.resolveKeyProperty` (the go-to / find-usages core): an identifier at the
 * caret resolves to the single `JSProperty` declaring that style key — from a
 * `styles.<key>` member access, from a destructured local, and from the destructuring
 * site itself; for local, imported, and aliased bindings.
 */
class RnStyleGotoDeclarationTest : BasePlatformTestCase() {

    private fun propAtCaret(): JSProperty? {
        val el = myFixture.file.findElementAt(myFixture.caretOffset) ?: return null
        return RnStyles.resolveKeyProperty(el)
    }

    fun testMemberAccessLocal() {
        myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ row: { flex: 1 } })\n" +
                "const x = styles.ro<caret>w",
        )
        val prop = propAtCaret()
        assertNotNull("expected a key property", prop)
        assertEquals("row", prop!!.name)
        assertEquals("Comp.tsx", prop.containingFile?.name)
    }

    fun testMemberAccessImported() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ title: { fontSize: 16 } })",
        )
        myFixture.configureByText(
            "About.tsx",
            "import { styles } from './styles'\nconst x = styles.tit<caret>le",
        )
        val prop = propAtCaret()
        assertEquals("title", prop?.name)
        assertEquals("styles.ts", prop?.containingFile?.name)
    }

    fun testDestructuredLocalAndSite() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ title: { fontSize: 16 } })",
        )
        // caret on the *use* of the destructured local
        myFixture.configureByText(
            "About.tsx",
            "import { styles } from './styles'\nconst { title } = styles\nconst x = tit<caret>le",
        )
        assertEquals("title", propAtCaret()?.name)

        // caret on the destructuring *site* must also resolve
        myFixture.configureByText(
            "About2.tsx",
            "import { styles } from './styles'\nconst { tit<caret>le } = styles",
        )
        assertEquals("title", propAtCaret()?.name)
    }

    fun testUnknownKeyAndUnrelatedQualifierResolveNull() {
        myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ row: { flex: 1 } })\n" +
                "const obj = { foo: 1 }\nconst x = obj.fo<caret>o",
        )
        assertNull("unrelated qualifier must not resolve", propAtCaret())

        myFixture.configureByText(
            "Comp2.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ row: { flex: 1 } })\n" +
                "const x = styles.no<caret>pe",
        )
        assertNull("unknown key must not resolve", propAtCaret())
    }
}
