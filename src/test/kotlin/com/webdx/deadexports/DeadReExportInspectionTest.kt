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

    fun testStaticRequireMemberKeepsAllNamesLive() {
        // Conservative require behavior: a STATIC `require('./b')` resolves to the barrel, and
        // member access (`.A`) is treated as STAR (consumedNames returns STAR for any non-import
        // reference). So even though only `.A` is touched, BOTH A and B stay live -> nothing
        // flagged. This pins the documented conservatism: we never narrow require member access.
        myFixture.addFileToProject("b/x.ts", "export const A = 1\nexport const B = 2\n")
        myFixture.addFileToProject("b/index.ts", "export { A, B } from './x'\n")
        myFixture.addFileToProject("use.ts", "const a = require('./b').A\n")
        val descriptions = descriptionsFor("b/index.ts")
        assertFalse("A must NOT be flagged (require member access is STAR), got: $descriptions",
            descriptions.any { it.contains("'A'") && it.contains("never used") })
        assertFalse("B must NOT be flagged (require member access is STAR), got: $descriptions",
            descriptions.any { it.contains("'B'") && it.contains("never used") })
    }

    fun testAliasedReExportConsumedNotFlagged() {
        // leaf exports `Inner`; the barrel re-exports it as `Outer`; a consumer imports `Outer`.
        // The re-export is live under its EXPORTED name `Outer` and must NOT be flagged. Before
        // the fix the inspection queried liveness by the SOURCE name `Inner`, which no consumer
        // references, so the live aliased re-export was wrongly greyed.
        myFixture.addFileToProject("leaf.ts", "export const Inner = 1\n")
        myFixture.addFileToProject("m/index.ts", "export { Inner as Outer } from '../leaf'\n")
        myFixture.addFileToProject("use.ts", "import { Outer } from './m'\nconst y = Outer\n")
        val descriptions = descriptionsFor("m/index.ts")
        assertFalse("consumed aliased re-export must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }

    fun testAliasedReExportNeverConsumedFlagged() {
        // Same aliased barrel but nobody consumes `Outer`. It must be flagged under the EXPORTED
        // name `Outer` (not the source name `Inner`).
        myFixture.addFileToProject("leaf.ts", "export const Inner = 1\n")
        myFixture.addFileToProject("m/index.ts", "export { Inner as Outer } from '../leaf'\n")
        val descriptions = descriptionsFor("m/index.ts")
        assertTrue("unconsumed aliased re-export should be flagged under 'Outer', got: $descriptions",
            descriptions.any { it.contains("'Outer'") && it.contains("never used") })
    }

    fun testNonReExportFileNoFlags() {
        myFixture.addFileToProject("plain.ts", "export const a = 1\nconst b = a\n")
        val descriptions = descriptionsFor("plain.ts")
        assertFalse("plain module -> nothing flagged, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }
}
