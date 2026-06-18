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

    fun testUnusedTypeExportsFlagged() {
        myFixture.addFileToProject("types.ts",
            "export interface IFace {}\nexport type TAlias = number\nexport enum E { X }\n")
        val descriptions = descriptionsFor("types.ts")
        assertTrue("unused interface should be flagged, got: $descriptions",
            descriptions.any { it.contains("'IFace'") && it.contains("never used") })
        assertTrue("unused type alias should be flagged, got: $descriptions",
            descriptions.any { it.contains("'TAlias'") && it.contains("never used") })
        assertTrue("unused enum should be flagged, got: $descriptions",
            descriptions.any { it.contains("'E'") && it.contains("never used") })
    }

    fun testUsedTypeExportNotFlagged() {
        myFixture.addFileToProject("types.ts", "export interface IFace { a: number }\n")
        myFixture.addFileToProject("use.ts", "import type { IFace } from './types'\nconst x: IFace = { a: 1 }\n")
        val descriptions = descriptionsFor("types.ts")
        assertFalse("used type must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }

    fun testReExportFromNotFlaggedByThisInspection() {
        // `export { x } from './y'` is a re-export link — owned by DeadReExportInspection, not this one.
        myFixture.addFileToProject("y.ts", "export const x = 1\n")
        myFixture.addFileToProject("barrel.ts", "export { x } from './y'\n")
        val descriptions = descriptionsFor("barrel.ts")
        assertFalse("DeadExportInspection must NOT flag a `… from` re-export, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }

    fun testSameFileUseByLiveExportGetsRedundantWarning() {
        // `helper` is used only inside its own module, but by `Main`, which IS consumed externally.
        // The symbol is alive but its `export` is redundant -> redundant-export warning, NOT a
        // dead-code grey-out. `Main` is externally consumed -> no diagnostic at all.
        myFixture.addFileToProject("m.ts",
            "export const helper = () => 1\nexport const Main = () => helper()\n")
        myFixture.addFileToProject("use.ts", "import { Main } from './m'\nconst x = Main\n")
        val descriptions = descriptionsFor("m.ts")
        assertTrue("helper must get the redundant-export warning, got: $descriptions",
            descriptions.any { it.contains("'helper'") && it.contains("'export' is redundant") })
        assertFalse("helper must NOT be greyed as never used, got: $descriptions",
            descriptions.any { it.contains("never used") })
        assertFalse("externally-consumed Main must NOT be reported at all, got: $descriptions",
            descriptions.any { it.contains("'Main'") })
    }

    fun testSameFileUseByDeadExportStillFlagged() {
        // `helper` is referenced only by `dead`, and `dead` has no external consumer either.
        // A reference from a dead sibling does not keep `helper` alive -> both flagged.
        myFixture.addFileToProject("m.ts",
            "export const helper = () => 1\nexport const dead = () => helper()\n")
        val descriptions = descriptionsFor("m.ts")
        assertTrue("helper referenced only by a dead sibling must be flagged, got: $descriptions",
            descriptions.any { it.contains("'helper'") && it.contains("never used") })
        assertTrue("the dead sibling itself must be flagged, got: $descriptions",
            descriptions.any { it.contains("'dead'") && it.contains("never used") })
    }

    fun testTypeUsedByLiveTypeGetsRedundantWarning() {
        // The motivating case: `Inner` is referenced only by `Outer`'s field, and `Outer` is
        // consumed externally. `Inner` is part of `Outer`'s public shape -> alive, but its `export`
        // is redundant -> redundant-export warning, not a dead-code grey-out.
        myFixture.addFileToProject("types.ts",
            "export interface Inner { a: number }\nexport interface Outer { inner: Inner }\n")
        myFixture.addFileToProject("use.ts",
            "import type { Outer } from './types'\nconst x: Outer = { inner: { a: 1 } }\n")
        val descriptions = descriptionsFor("types.ts")
        assertTrue("Inner must get the redundant-export warning, got: $descriptions",
            descriptions.any { it.contains("'Inner'") && it.contains("'export' is redundant") })
        assertFalse("Inner must NOT be greyed as never used, got: $descriptions",
            descriptions.any { it.contains("never used") })
        assertFalse("externally-consumed Outer must NOT be reported at all, got: $descriptions",
            descriptions.any { it.contains("'Outer'") })
    }

    fun testSelfReferentialTypeStillFlagged() {
        // A recursive type referencing only itself has no external consumer; the self-reference
        // does not keep it alive -> flagged.
        myFixture.addFileToProject("node.ts", "export interface Node { next: Node | null }\n")
        val descriptions = descriptionsFor("node.ts")
        assertTrue("self-referential unused type must be flagged, got: $descriptions",
            descriptions.any { it.contains("'Node'") && it.contains("never used") })
    }

    fun testAnonymousDefaultExportFlagged() {
        // Anonymous default -> ES6ExportDefaultAssignment.namedElement is null, anchor falls back
        // to the element. Must still be flagged under the name 'default'.
        myFixture.addFileToProject("anon.tsx", "export default () => null\n")
        val descriptions = descriptionsFor("anon.tsx")
        assertTrue("anonymous default export should be flagged, got: $descriptions",
            descriptions.any { it.contains("'default'") && it.contains("never used") })
    }

    fun testMultipleBindingsEachFlagged() {
        // `export const A = 1, B = 2` -> two exported bindings, each queried/flagged independently.
        myFixture.addFileToProject("multi.ts", "export const A = 1, B = 2\n")
        val descriptions = descriptionsFor("multi.ts")
        assertTrue("A should be flagged, got: $descriptions",
            descriptions.any { it.contains("'A'") && it.contains("never used") })
        assertTrue("B should be flagged, got: $descriptions",
            descriptions.any { it.contains("'B'") && it.contains("never used") })
    }
}
