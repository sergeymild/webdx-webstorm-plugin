package com.intch.cssmodules

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Functional tests for `styles.<caret>` member completion served from the CSS
 * module file. Files live at the source root so that `./X.module.scss` resolves.
 */
class CssModuleCompletionTest : BasePlatformTestCase() {

    fun testCompletesClassNamesFromModule() {
        myFixture.addFileToProject("Comp.module.scss", ".container { } .wrapper { } .mobileView { }")
        myFixture.configureByText(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nfunction f() { return styles.<caret>; }",
        )
        myFixture.completeBasic()
        val suggestions = myFixture.lookupElementStrings ?: emptyList()
        assertTrue(
            "expected the module classes in the popup, got: $suggestions",
            suggestions.containsAll(listOf("container", "wrapper", "mobileView")),
        )
    }

    fun testCompletionContainsOnlyModuleClasses() {
        // The contributor consumes & drops every other contributor's results,
        // so only the real CSS-module classes should remain.
        myFixture.addFileToProject("Comp.module.scss", ".alpha { } .beta { }")
        myFixture.configureByText(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nfunction f() { return styles.<caret>; }",
        )
        myFixture.completeBasic()
        val suggestions = myFixture.lookupElementStrings ?: emptyList()
        assertEquals(
            "popup should contain exactly the module classes",
            setOf("alpha", "beta"),
            suggestions.toSet(),
        )
    }

    fun testNoModuleCompletionForUnrelatedQualifier() {
        myFixture.addFileToProject("Comp.module.scss", ".alpha { } .beta { }")
        myFixture.configureByText(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nconst obj = { foo: 1 };\nfunction f() { return obj.<caret>; }",
        )
        myFixture.completeBasic()
        val suggestions = myFixture.lookupElementStrings ?: emptyList()
        assertFalse(
            "module classes must not leak onto an unrelated qualifier, got: $suggestions",
            suggestions.contains("alpha") || suggestions.contains("beta"),
        )
    }

    fun testCompletesImportedClassesFromAtImport() {
        myFixture.addFileToProject("common.module.scss", ".nextButton { } .note { }")
        myFixture.addFileToProject(
            "Comp.module.scss",
            "@import './common.module.scss';\n.local { }",
        )
        myFixture.configureByText(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nfunction f() { return styles.<caret>; }",
        )
        myFixture.completeBasic()
        val suggestions = myFixture.lookupElementStrings ?: emptyList()
        assertTrue(
            "expected own + imported classes, got: $suggestions",
            suggestions.containsAll(listOf("local", "nextButton", "note")),
        )
    }
}
