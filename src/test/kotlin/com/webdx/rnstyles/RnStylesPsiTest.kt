package com.webdx.rnstyles

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** PSI tests for the RnStyles helpers, on in-memory JS/TS fixtures. */
class RnStylesPsiTest : BasePlatformTestCase() {

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
}
