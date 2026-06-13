package com.webdx.rnstyles

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RnStyleUnknownKeyInspectionTest : BasePlatformTestCase() {

    fun testUnknownKeyInlineReported() {
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ container: { flex: 1 } })\n" +
                "const a = styles.container\nconst b = styles.doesNotExist",
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(RnStyleUnknownKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "expected 'doesNotExist' flagged, got: $descriptions",
            descriptions.any { it.contains("Unknown style key 'doesNotExist'") },
        )
        assertFalse(
            "real key 'container' must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'container'") },
        )
    }

    fun testUnknownKeyImportedReported() {
        myFixture.addFileToProject(
            "src/styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ title: { fontSize: 16 } })",
        )
        val tsx = myFixture.addFileToProject(
            "src/About.tsx",
            "import { styles } from './styles'\nconst a = styles.title\nconst b = styles.nope",
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(RnStyleUnknownKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue("expected 'nope' flagged, got: $descriptions", descriptions.any { it.contains("Unknown style key 'nope'") })
        assertFalse("real key 'title' must NOT be flagged, got: $descriptions", descriptions.any { it.contains("'title'") })
    }

    fun testUnrelatedMemberAccessNotFlagged() {
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            "const obj = { foo: 1 }\nconst x = obj.bar",
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(RnStyleUnknownKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse("unrelated member access must NOT be flagged, got: $descriptions", descriptions.any { it.contains("Unknown style key") })
    }
}
