package com.webdx.deadexports

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DeadExportInspectionTest : BasePlatformTestCase() {

    private fun descriptionsFor(path: String): List<String> {
        val file = myFixture.findFileInTempDir(path)
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.enableInspections(DeadExportInspection())
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    fun testUnusedInlineValueExportsFlagged() {
        // SomeFun + Some are exported but reached only by a dead barrel `export *` -> flagged.
        myFixture.addFileToProject("components/SomeFun.tsx",
            "export const SomeFun = () => null\nSomeFun.displayName = 'SomeFun'\nexport function Some() {}\n")
        myFixture.addFileToProject("components/index.ts", "export * from './SomeFun'\n")
        val descriptions = descriptionsFor("components/SomeFun.tsx")
        assertTrue("SomeFun should be flagged, got: $descriptions",
            descriptions.any { it.contains("'SomeFun'") && it.contains("never used") })
        assertTrue("Some should be flagged, got: $descriptions",
            descriptions.any { it.contains("'Some'") && it.contains("never used") })
    }

    fun testLiveInlineExportNotFlagged() {
        // A real named import keeps the export live -> not flagged.
        myFixture.addFileToProject("components/SomeFun.tsx", "export const SomeFun = () => null\n")
        myFixture.addFileToProject("components/index.ts", "export * from './SomeFun'\n")
        myFixture.addFileToProject("use.tsx", "import { SomeFun } from './components'\nconst x = SomeFun\n")
        val descriptions = descriptionsFor("components/SomeFun.tsx")
        assertFalse("live export must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }

    fun testUnusedDefaultExportFlagged() {
        myFixture.addFileToProject("widget.tsx", "export default function Widget() { return null }\n")
        val descriptions = descriptionsFor("widget.tsx")
        assertTrue("unused default export should be flagged, got: $descriptions",
            descriptions.any { it.contains("'default'") && it.contains("never used") })
    }

    fun testNextPageDefaultNotFlagged() {
        // A Next.js page default is an entry point (next.config present) -> whole file skipped.
        myFixture.addFileToProject("next.config.js", "module.exports = {}\n")
        myFixture.addFileToProject("pages/p/index.tsx", "export default function Page() { return null }\n")
        val descriptions = descriptionsFor("pages/p/index.tsx")
        assertFalse("Next.js page default must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }

    fun testUnusedLocalExportSpecifierFlagged() {
        // `const local = 1; export { local as pub }` — exported name is `pub`, unused -> flagged.
        myFixture.addFileToProject("m.ts", "const local = 1\nexport { local as pub }\n")
        val descriptions = descriptionsFor("m.ts")
        assertTrue("unused local re-export 'pub' should be flagged, got: $descriptions",
            descriptions.any { it.contains("'pub'") && it.contains("never used") })
        assertFalse("must flag the exported name 'pub', not the source 'local', got: $descriptions",
            descriptions.any { it.contains("'local'") })
    }

    fun testUsedLocalExportSpecifierNotFlagged() {
        myFixture.addFileToProject("m.ts", "const local = 1\nexport { local as pub }\n")
        myFixture.addFileToProject("use.ts", "import { pub } from './m'\nconst x = pub\n")
        val descriptions = descriptionsFor("m.ts")
        assertFalse("consumed local re-export must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }
}
