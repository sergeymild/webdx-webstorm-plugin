package com.webdx.deadexports

import com.intellij.lang.ecmascript6.psi.ES6ExportSpecifier
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [BarrelReExportUsages.collect] returns ONLY the direct, single-hop sites that draw an exported
 * name from a given barrel file — the next-level re-export that forwards it and any module that
 * imports it straight from this barrel. It is deliberately NOT transitive: consumers that reach
 * the name through a further barrel, and deep imports of the source module, are excluded.
 */
class BarrelReExportUsagesTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("comp/UniversalCard.tsx", "export const UniversalCard = () => null\n")
        // The clicked barrel.
        myFixture.addFileToProject("comp/index.ts", "export { UniversalCard } from './UniversalCard'\n")
        // Direct re-exporter of comp/index.ts — SHOULD appear.
        myFixture.addFileToProject("parent/index.ts", "export { UniversalCard } from '../comp'\n")
        // Direct importer of comp/index.ts — SHOULD appear, and only ONCE (the import site, not the body use).
        myFixture.addFileToProject("Direct.tsx", "import { UniversalCard } from './comp'\nexport const D = () => UniversalCard\n")
        // Reaches the name only through parent/index.ts (references parent, not comp) — must NOT appear.
        myFixture.addFileToProject("Chain.tsx", "import { UniversalCard } from './parent'\nexport const C = () => UniversalCard\n")
        // Deep import: references the source module, not comp/index.ts — must NOT appear.
        myFixture.addFileToProject("Deep.tsx", "import { UniversalCard } from './comp/UniversalCard'\nexport const E = () => UniversalCard\n")
    }

    private fun barrel(path: String): PsiFile {
        val vf = myFixture.findFileInTempDir(path)
        return PsiManager.getInstance(project).findFile(vf)!!
    }

    fun testCollectsOnlyDirectSingleHopSites() {
        val usages = BarrelReExportUsages.collect(barrel("comp/index.ts"), "UniversalCard")
        val files = usages.map { it.containingFile.name }.toSet()

        assertTrue("direct re-exporter parent/index.ts should appear, got: $files",
            usages.any { it.containingFile.virtualFile?.path?.contains("/parent/") == true })
        assertTrue("direct importer Direct.tsx should appear, got: $files", "Direct.tsx" in files)
        assertFalse("chain-only consumer Chain.tsx must NOT appear, got: $files", "Chain.tsx" in files)
        assertFalse("deep importer Deep.tsx must NOT appear, got: $files", "Deep.tsx" in files)
    }

    fun testDoesNotExpandIntoConsumerBodyUses() {
        val usages = BarrelReExportUsages.collect(barrel("comp/index.ts"), "UniversalCard")
        // Single-hop: only the import site in Direct.tsx, NOT the `() => UniversalCard` body use.
        val inDirect = usages.filter { it.containingFile.name == "Direct.tsx" }
        assertEquals("Direct.tsx should contribute exactly its import site: ${inDirect.map { it.text }}",
            1, inDirect.size)
    }

    fun testNameNobodyDrawsHasNoSites() {
        val usages = BarrelReExportUsages.collect(barrel("comp/index.ts"), "Nonexistent")
        assertTrue("a name no site draws from this barrel must yield nothing, got: ${usages.map { it.text }}",
            usages.isEmpty())
    }

    fun testResolveSourceFindsTheComponentDeclaration() {
        val spec = PsiTreeUtil.findChildrenOfType(barrel("comp/index.ts"), ES6ExportSpecifier::class.java)
            .first { it.referenceName == "UniversalCard" }
        val source = BarrelReExportUsages.resolveSource(spec)
        assertNotNull("resolveSource should find the component declaration", source)
        assertEquals("should resolve into the component file", "UniversalCard.tsx", source!!.containingFile.name)
    }
}
