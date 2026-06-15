package com.webdx.rnstyles

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RnStyleUnusedKeyInspectionTest : BasePlatformTestCase() {

    fun testUnusedKeyInlineReported() {
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ used: { flex: 1 }, boxRadius: { borderRadius: 4 } })\n" +
                "const x = styles.used",
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(RnStyleUnusedKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        // Only the unused top-level key must be flagged — NOT the used key, and NOT the
        // nested value-object keys (`flex`, `borderRadius`), which are JSProperty nodes too.
        val relevant = listOf("flex", "borderRadius", "used", "boxRadius")
        val flagged = descriptions
            .filter { it.contains("not used") }
            .mapNotNull { d -> relevant.firstOrNull { d.contains("'$it'") } }
        assertEquals("only 'boxRadius' should be flagged, got: $descriptions", listOf("boxRadius"), flagged)
    }

    fun testKeyUsedOnlyViaDestructuringNotFlagged() {
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ title: { fontSize: 16 } })\n" +
                "const { title } = styles\nconst x = title",
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(RnStyleUnusedKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "'title' is used via destructuring and must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'title'") },
        )
    }

    fun testUnusedKeyInDefaultExportReported() {
        myFixture.addFileToProject(
            "src/Consumer.tsx",
            "import b from './bstyles'\nconst x = b.used",
        )
        val styles = myFixture.addFileToProject(
            "src/bstyles.ts",
            "import { StyleSheet } from 'react-native'\nexport default StyleSheet.create({ used: { flex: 1 }, dead: { flex: 1 } })",
        )
        myFixture.configureFromExistingVirtualFile(styles.virtualFile)
        myFixture.enableInspections(RnStyleUnusedKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "'used' is referenced by a default-import consumer and must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'used'") },
        )
        assertTrue(
            "'dead' is never referenced and SHOULD be flagged, got: $descriptions",
            descriptions.any { it.contains("'dead'") && it.contains("not used") },
        )
    }

    fun testNoStyleSheetNoFlags() {
        val tsx = myFixture.addFileToProject(
            "src/Plain.tsx",
            "const obj = { a: 1, b: 2 }\nconst x = obj.a",
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(RnStyleUnusedKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "no StyleSheet object -> nothing flagged, got: $descriptions",
            descriptions.any { it.contains("not used") },
        )
    }

    fun testExportedKeyUsedViaImporterNotFlagged() {
        myFixture.addFileToProject(
            "src/About.tsx",
            "import { styles } from './styles'\nconst x = styles.used",
        )
        val stylesTs = myFixture.addFileToProject(
            "src/styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ used: { flex: 1 }, dead: { flex: 1 } })",
        )
        myFixture.configureFromExistingVirtualFile(stylesTs.virtualFile)
        myFixture.enableInspections(RnStyleUnusedKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "'used' is referenced by an importer and must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'used'") },
        )
        assertTrue(
            "'dead' is never referenced and SHOULD be flagged, got: $descriptions",
            descriptions.any { it.contains("'dead'") && it.contains("not used") },
        )
    }
}
