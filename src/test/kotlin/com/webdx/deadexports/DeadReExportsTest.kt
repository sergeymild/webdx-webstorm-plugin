package com.webdx.deadexports

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DeadReExportsTest : BasePlatformTestCase() {

    private fun reExportDecls(text: String): List<ES6ExportDeclaration> {
        val file = myFixture.configureByText("m.ts", text)
        return PsiTreeUtil.findChildrenOfType(file, ES6ExportDeclaration::class.java)
            .filter { it.isReExport }
    }

    fun testNamedReExportNames() {
        val decl = reExportDecls("export { a, b as c } from './x'").single()
        assertEquals(listOf("a", "b"), DeadReExports.reExportedSourceNames(decl))
    }

    fun testDefaultReExportName() {
        val decl = reExportDecls("export { default } from './x'").single()
        assertEquals(listOf("default"), DeadReExports.reExportedSourceNames(decl))
    }

    fun testExportAllIsStar() {
        val decl = reExportDecls("export * from './x'").single()
        assertEquals(listOf(DeadReExports.STAR), DeadReExports.reExportedSourceNames(decl))
    }

    private fun classifyRefIn(consumer: String, moduleName: String = "x"): DeadReExports.RefKind {
        myFixture.addFileToProject("$moduleName.ts", "export const a = 1\nexport default 2\n")
        val file = myFixture.configureByText("consumer.ts", consumer)
        val moduleFile = myFixture.findFileInTempDir("$moduleName.ts")
            .let { com.intellij.psi.PsiManager.getInstance(project).findFile(it)!! }
        val refs = com.intellij.psi.search.searches.ReferencesSearch
            .search(moduleFile, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
            .findAll()
            .filter { it.element.containingFile == file }
        return DeadReExports.classify(refs.first().element)
    }

    fun testImportIsRealConsumer() {
        assertEquals(DeadReExports.RefKind.RealConsumer, classifyRefIn("import { a } from './x'\nconst y = a"))
    }

    fun testRequireIsRealConsumer() {
        assertEquals(DeadReExports.RefKind.RealConsumer, classifyRefIn("const a = require('./x').a"))
    }

    fun testReExportSiteIsReExport() {
        val kind = classifyRefIn("export { a } from './x'")
        assertTrue("expected ReExportSite, got $kind", kind is DeadReExports.RefKind.ReExportSite)
    }

    private fun analyzer() = DeadReExports.Analyzer(project)

    private fun moduleFile(path: String): com.intellij.psi.PsiFile =
        com.intellij.psi.PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir(path))!!

    fun testDeadBarrelBypassedByDeepRequire() {
        // barrel re-exports the component; the only consumer requires the .tsx DIRECTLY.
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\nexport default Screen\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen'\n")
        myFixture.addFileToProject("nav.tsx", "const C = require('./a/Screen').Screen\n")
        myFixture.configureByText("trigger.ts", "")
        assertFalse(analyzer().isLive(moduleFile("a/index.ts"), "Screen"))
    }

    fun testBarrelKeptAliveByImport() {
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen'\n")
        myFixture.addFileToProject("use.ts", "import { Screen } from './a'\nconst x = Screen\n")
        myFixture.configureByText("trigger.ts", "")
        assertTrue(analyzer().isLive(moduleFile("a/index.ts"), "Screen"))
    }

    fun testTransitiveChainLiveRoot() {
        // leaf -> mid (re-export) -> top (re-export) -> real import of top.
        myFixture.addFileToProject("leaf.ts", "export const K = 1\n")
        myFixture.addFileToProject("mid.ts", "export { K } from './leaf'\n")
        myFixture.addFileToProject("top.ts", "export { K } from './mid'\n")
        myFixture.addFileToProject("use.ts", "import { K } from './top'\nconst x = K\n")
        myFixture.configureByText("trigger.ts", "")
        assertTrue("mid should be live via top's importer", analyzer().isLive(moduleFile("mid.ts"), "K"))
    }

    fun testTransitiveChainAllDead() {
        myFixture.addFileToProject("leaf.ts", "export const K = 1\n")
        myFixture.addFileToProject("mid.ts", "export { K } from './leaf'\n")
        myFixture.addFileToProject("top.ts", "export { K } from './mid'\n") // nobody imports top
        myFixture.configureByText("trigger.ts", "")
        assertFalse(analyzer().isLive(moduleFile("mid.ts"), "K"))
    }

    fun testCycleTerminates() {
        myFixture.addFileToProject("p.ts", "export { Z } from './q'\n")
        myFixture.addFileToProject("q.ts", "export { Z } from './p'\n") // mutual, no real consumer
        myFixture.configureByText("trigger.ts", "")
        assertFalse(analyzer().isLive(moduleFile("p.ts"), "Z"))
    }

    fun testNamespaceImportKeepsAllLive() {
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen'\n")
        myFixture.addFileToProject("use.ts", "import * as ns from './a'\nconst x = ns\n")
        myFixture.configureByText("trigger.ts", "")
        assertTrue(analyzer().isLive(moduleFile("a/index.ts"), "Screen"))
    }

    fun testExportStarConsumerKeepsLiveWhenItsRootIsLive() {
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen'\n")
        myFixture.addFileToProject("agg.ts", "export * from './a'\n")          // re-export site
        myFixture.addFileToProject("use.ts", "import { Screen } from './agg'\nconst x = Screen\n")
        myFixture.configureByText("trigger.ts", "")
        assertTrue(analyzer().isLive(moduleFile("a/index.ts"), "Screen"))
    }

    // Regression: a LIVE 2-cycle (a <-> b) with a real consumer importing b directly.
    // A shared Analyzer must not cache a's `false` that was only produced by the cycle
    // cutoff (visited-guard) while b was an in-progress ancestor. Both query orders must
    // see both nodes as live. See DeadReExports.isLive's hitCycle plumbing.
    fun testLiveCycleNotPoisonedByMemo() {
        myFixture.addFileToProject("a.ts", "export { K } from './b'\n")
        myFixture.addFileToProject("b.ts", "export { K } from './a'\nexport const _x = 1\n")
        myFixture.addFileToProject("use.ts", "import { K } from './b'\nconst y = K\n")
        myFixture.configureByText("trigger.ts", "")
        val az = analyzer() // ONE shared analyzer across both queries
        // query b first (the directly-imported node), then a (reached only via the cycle)
        val bLive = az.isLive(moduleFile("b.ts"), "K")
        val aLive = az.isLive(moduleFile("a.ts"), "K")
        assertTrue("b is imported directly -> live", bLive)
        assertTrue("a is live via the cycle to b's importer -> must not be poisoned by memo", aLive)
    }

    fun testLiveCycleNotPoisonedByMemoReverseOrder() {
        myFixture.addFileToProject("a.ts", "export { K } from './b'\n")
        myFixture.addFileToProject("b.ts", "export { K } from './a'\nexport const _x = 1\n")
        myFixture.addFileToProject("use.ts", "import { K } from './b'\nconst y = K\n")
        myFixture.configureByText("trigger.ts", "")
        val az = analyzer() // ONE shared analyzer across both queries
        // query a first (reached only via the cycle), then b (directly imported)
        val aLive = az.isLive(moduleFile("a.ts"), "K")
        val bLive = az.isLive(moduleFile("b.ts"), "K")
        assertTrue("a is live via the cycle to b's importer -> must not be poisoned by memo", aLive)
        assertTrue("b is imported directly -> live", bLive)
    }
}
