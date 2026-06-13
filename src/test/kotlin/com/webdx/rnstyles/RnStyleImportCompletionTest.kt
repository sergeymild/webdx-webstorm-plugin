package com.webdx.rnstyles

import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Completion-driven auto-import of a sibling RN StyleSheet module: typing `styles`
 * offers an entry when a sibling file exports `styles`, and selecting it inserts
 * `import { styles } from './styles'` (direct document edit, no TS service).
 */
class RnStyleImportCompletionTest : BasePlatformTestCase() {

    private fun completeAndPick(name: String) {
        val items = myFixture.completeBasic()
        if (items == null) return // single match auto-inserted
        val lookup = myFixture.lookup as LookupImpl
        lookup.currentItem = lookup.items.first { it.lookupString == name }
        myFixture.type('\n')
    }

    fun testOffersStylesWhenSiblingExportsIt() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ row: { flex: 1 } })",
        )
        myFixture.configureByText("About.tsx", "function f() { return styles<caret> }")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected a 'styles' entry, got: $items", items.contains("styles"))
    }

    fun testSelectingInsertsNamedImport() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ row: { flex: 1 } })",
        )
        myFixture.configureByText("About.tsx", "function f() { return styles<caret> }")
        completeAndPick("styles")
        val text = myFixture.file.text
        assertTrue("named import missing, got:\n$text", text.contains("import { styles } from './styles'"))
        assertTrue("reference not kept, got:\n$text", text.contains("return styles"))
    }

    fun testNoSiblingNoOffer() {
        myFixture.configureByText("About.tsx", "function f() { return styles<caret> }")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertFalse("must not offer a styles import without a sibling, got: $items", items.contains("styles"))
    }

    fun testDoesNotDuplicateExistingImport() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ row: { flex: 1 } })",
        )
        myFixture.configureByText(
            "About.tsx",
            "import { styles } from './styles'\nfunction f() { return styles<caret> }",
        )
        myFixture.completeBasic()
        val text = myFixture.file.text
        val occurrences = Regex("""import\s*\{\s*styles\s*\}\s*from\s*'\./styles'""").findAll(text).count()
        assertEquals("import duplicated, got:\n$text", 1, occurrences)
    }
}
