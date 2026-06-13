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
        assertTrue(
            "expected 'boxRadius' flagged unused, got: $descriptions",
            descriptions.any { it.contains("'boxRadius'") && it.contains("not used") },
        )
        assertFalse(
            "'used' must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'used'") },
        )
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
