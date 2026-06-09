package com.intch.cssmodules

import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Functional tests for completion-driven auto-import of a sibling CSS module:
 * typing a bare identifier offers a module-named entry that, when selected,
 * renames what you typed to that binding AND adds the import. Fully testable
 * because the import line is inserted by direct document editing (no TS service).
 */
class CssModuleAutoImportCompletionTest : BasePlatformTestCase() {

    /** Complete, then pick [name] — tolerating the single-match auto-insert (no popup). */
    private fun completeAndPick(name: String) {
        val items = myFixture.completeBasic()
        if (items == null) return // single match was auto-inserted already
        val lookup = myFixture.lookup as LookupImpl
        lookup.currentItem = lookup.items.first { it.lookupString == name }
        myFixture.type('\n')
    }

    fun testOffersModuleNamedEntryWhenTypingStyles() {
        // Two siblings keep the popup open so we can inspect the entries.
        myFixture.addFileToProject("Sidebar.module.scss", ".foo { }")
        myFixture.addFileToProject("Header.module.scss", ".bar { }")
        myFixture.configureByText("Profile.tsx", "function f() { return styles<caret> }")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected module entries while typing 'styles', got: $items", items.containsAll(listOf("sidebar", "header")))
    }

    fun testSelectingRenamesAndAddsImport() {
        myFixture.addFileToProject("Sidebar.module.scss", ".foo { }")
        myFixture.configureByText("Profile.tsx", "function f() { return styles<caret> }")
        completeAndPick("sidebar")

        val text = myFixture.file.text
        assertTrue("import missing, got:\n$text", text.contains("import sidebar from './Sidebar.module.scss'"))
        assertTrue("reference not renamed, got:\n$text", text.contains("return sidebar"))
        assertFalse("typed name left behind, got:\n$text", text.contains("return styles"))
    }

    fun testMatchingModuleKeepsStylesAndImportsOwnModule() {
        myFixture.addFileToProject("Profile.module.scss", ".foo { }")
        myFixture.configureByText("Profile.tsx", "function f() { return styles<caret> }")
        completeAndPick("styles")
        val text = myFixture.file.text
        assertTrue("import missing, got:\n$text", text.contains("import styles from './Profile.module.scss'"))
        assertTrue("reference not completed, got:\n$text", text.contains("return styles"))
    }

    fun testDoesNotDuplicateExistingImport() {
        myFixture.addFileToProject("Sidebar.module.scss", ".foo { }")
        myFixture.configureByText(
            "Profile.tsx",
            "import sidebar from './Sidebar.module.scss';\nfunction f() { return styles<caret> }",
        )
        // The binding already resolves, so we must not insert a second import.
        completeAndPick("sidebar")
        val text = myFixture.file.text
        val occurrences = Regex("""import\s+sidebar\s+from""").findAll(text).count()
        assertEquals("import duplicated, got:\n$text", 1, occurrences)
    }
}
