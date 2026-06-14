package com.webdx.rnstyles

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.FakePsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** PSI tests for the RnStyles helpers, on in-memory JS/TS fixtures. */
class RnStylesPsiTest : BasePlatformTestCase() {

    /**
     * Regression: the background usage-highlighter can hand `resolveKeyProperty` a childless leaf
     * whose `getText()` returns null (a Java platform type). It must return null, not NPE.
     */
    fun testResolveKeyPropertyNullTextLeafDoesNotThrow() {
        val nullTextLeaf = object : FakePsiElement() {
            override fun getParent(): PsiElement? = null
            // getFirstChild() and getText() both default to null in FakePsiElement.
        }
        assertNull(RnStyles.resolveKeyProperty(nullTextLeaf))
    }

    fun testFindsStyleSheetAndKeys() {
        val file = myFixture.configureByText(
            "styles.ts",
            """
            import { StyleSheet } from 'react-native'
            export const styles = StyleSheet.create({
                container: { flex: 1 },
                title: { fontSize: 16 },
            })
            """.trimIndent(),
        )
        val sheets = RnStyles.fileStyleSheets(file)
        assertEquals(setOf("styles"), sheets.keys)
        assertEquals(listOf("container", "title"), RnStyles.styleKeys(sheets.getValue("styles")))
        assertNotNull(RnStyles.keyProperty(sheets.getValue("styles"), "title"))
        assertNull(RnStyles.keyProperty(sheets.getValue("styles"), "nope"))
    }

    fun testIgnoresNonStyleSheetObjects() {
        val file = myFixture.configureByText(
            "styles.ts",
            "const notStyles = ({ a: 1 });\nconst x = SomethingElse.create({ b: 2 });",
        )
        assertTrue(RnStyles.fileStyleSheets(file).isEmpty())
    }

    fun testFindsNonExportedConst() {
        val file = myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\nconst expStyles = StyleSheet.create({ row: { flex: 1 } })",
        )
        assertEquals(setOf("expStyles"), RnStyles.fileStyleSheets(file).keys)
    }

    fun testResolvesLocalBinding() {
        val file = myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\nconst styles = StyleSheet.create({ row: { flex: 1 } })\nconst x = styles.row",
        )
        val obj = RnStyles.resolveStyleSheetForBinding(file, "styles")
        assertNotNull(obj)
        assertEquals(listOf("row"), RnStyles.styleKeys(obj!!))
    }

    fun testResolvesNamedImportBinding() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ title: { fontSize: 16 } })",
        )
        val file = myFixture.configureByText(
            "About.tsx",
            "import { styles } from './styles'\nconst x = styles.title",
        )
        val obj = RnStyles.resolveStyleSheetForBinding(file, "styles")
        assertNotNull(obj)
        assertEquals(listOf("title"), RnStyles.styleKeys(obj!!))
    }

    fun testResolvesAliasedNamedImportBinding() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ box: { flex: 1 } })",
        )
        val file = myFixture.configureByText(
            "About.tsx",
            "import { styles as s } from './styles'\nconst x = s.box",
        )
        val obj = RnStyles.resolveStyleSheetForBinding(file, "s")
        assertNotNull(obj)
        assertEquals(listOf("box"), RnStyles.styleKeys(obj!!))
    }

    fun testUnknownBindingResolvesNull() {
        val file = myFixture.configureByText("About.tsx", "const x = nope.title")
        assertNull(RnStyles.resolveStyleSheetForBinding(file, "nope"))
    }

    fun testBindingsInFileIncludesLocalAndImported() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ title: { fontSize: 16 } })",
        )
        val file = myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "import { styles } from './styles'\n" +
                "const local = StyleSheet.create({ row: { flex: 1 } })\n" +
                "const x = styles.title\nconst y = local.row",
        )
        val bindings = RnStyles.bindingsInFile(file)
        assertEquals(setOf("styles", "local"), bindings.keys)
        assertEquals(listOf("title"), RnStyles.styleKeys(bindings.getValue("styles")))
        assertEquals(listOf("row"), RnStyles.styleKeys(bindings.getValue("local")))
    }

    fun testCollectUsedKeysInline() {
        val file = myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ used: { flex: 1 }, dead: { flex: 1 }, viaDestr: { flex: 1 } })\n" +
                "const { viaDestr } = styles\nconst x = styles.used\nconst y = viaDestr",
        )
        val obj = RnStyles.fileStyleSheets(file).getValue("styles")
        assertEquals(setOf("used", "viaDestr"), RnStyles.collectUsedKeys(obj))
    }

    fun testCollectUsedKeysExportedViaImporter() {
        val stylesPsi = myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ used: { flex: 1 }, dead: { flex: 1 } })",
        )
        myFixture.addFileToProject(
            "About.tsx",
            "import { styles } from './styles'\nconst x = styles.used",
        )
        val obj = RnStyles.fileStyleSheets(stylesPsi).getValue("styles")
        assertEquals(setOf("used"), RnStyles.collectUsedKeys(obj))
    }

    fun testCollectUsedKeysExportedViaAliasedImporter() {
        val stylesPsi = myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ used: { flex: 1 }, dead: { flex: 1 } })",
        )
        myFixture.addFileToProject(
            "About.tsx",
            "import { styles as s } from './styles'\nconst x = s.used",
        )
        val obj = RnStyles.fileStyleSheets(stylesPsi).getValue("styles")
        assertEquals(setOf("used"), RnStyles.collectUsedKeys(obj))
    }
}
