package com.webdx.deadexports

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DeadReExportInspectionTest : BasePlatformTestCase() {

    private fun descriptionsFor(barrelPath: String): List<String> {
        val barrel = myFixture.findFileInTempDir(barrelPath)
        myFixture.configureFromExistingVirtualFile(barrel)
        myFixture.enableInspections(DeadReExportInspection())
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    fun testDeadBarrelFlagged() {
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\nexport default Screen\n")
        myFixture.addFileToProject("a/index.ts",
            "export { Screen } from './Screen'\nexport { default } from './Screen'\n")
        myFixture.addFileToProject("nav.tsx", "const C = require('./a/Screen').Screen\n") // bypasses barrel
        val descriptions = descriptionsFor("a/index.ts")
        assertTrue("Screen re-export should be flagged, got: $descriptions",
            descriptions.any { it.contains("'Screen'") && it.contains("never used") })
        assertTrue("default re-export should be flagged, got: $descriptions",
            descriptions.any { it.contains("'default'") && it.contains("never used") })
    }

    fun testLiveBarrelNotFlagged() {
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen'\n")
        myFixture.addFileToProject("use.ts", "import { Screen } from './a'\nconst x = Screen\n")
        val descriptions = descriptionsFor("a/index.ts")
        assertFalse("live re-export must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'Screen'") && it.contains("never used") })
    }

    fun testPartiallyDeadBarrel() {
        myFixture.addFileToProject("a/x.ts", "export const Live = 1\nexport const Dead = 2\n")
        myFixture.addFileToProject("a/index.ts", "export { Live, Dead } from './x'\n")
        myFixture.addFileToProject("use.ts", "import { Live } from './a'\nconst x = Live\n")
        val descriptions = descriptionsFor("a/index.ts")
        assertTrue("Dead should be flagged, got: $descriptions",
            descriptions.any { it.contains("'Dead'") && it.contains("never used") })
        assertFalse("Live must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'Live'") && it.contains("never used") })
    }

    fun testNonReExportFileNoFlags() {
        myFixture.addFileToProject("plain.ts", "export const a = 1\nconst b = a\n")
        val descriptions = descriptionsFor("plain.ts")
        assertFalse("plain module -> nothing flagged, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }
}
