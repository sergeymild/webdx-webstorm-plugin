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

    // Repro: barrel re-exports the component with an EXPLICIT .tsx extension in the from-clause,
    // and the component file itself imports back from the barrel (a file<->barrel cycle, exactly
    // like AlertNotification.tsx <-> design/index.ts). The leaf export must still be live.
    fun testLeafLiveThroughExtensionedReExportWithBarrelCycle() {
        myFixture.addFileToProject("a/Screen.tsx",
            "import { helper } from '../index'\nexport const Screen = () => helper\n")
        myFixture.addFileToProject("a/index.ts",
            "export { helper } from './helper'\nexport { Screen } from './Screen.tsx'\n")
        myFixture.addFileToProject("a/helper.ts", "export const helper = 1\n")
        myFixture.addFileToProject("use.ts", "import { Screen } from './a'\nconst x = Screen\n")
        myFixture.configureByText("trigger.ts", "")
        assertTrue("leaf export reached via extensioned re-export must be live",
            analyzer().isLive(moduleFile("a/Screen.tsx"), "Screen"))
    }

    // Root-cause regression: the only consumer imports the leaf's name through a tsconfig PATH
    // ALIAS ('@ds') mapped to the barrel, never via a relative path. The file-based isLive walk
    // misses alias importers (ReferencesSearch on a file is keyed on the file-name word, which
    // '@ds' does not contain), so the alias-safe symbol search must report the leaf live.
    fun testSymbolConsumerFoundThroughPathAlias() {
        myFixture.addFileToProject("tsconfig.json", """
            {
              "compilerOptions": {
                "baseUrl": ".",
                "paths": { "@ds": ["./a/index.ts"], "@ds/*": ["./a/*"] }
              }
            }
        """.trimIndent())
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen.tsx'\n")
        myFixture.addFileToProject("use.tsx", "import { Screen } from '@ds'\nconst x = Screen\n")
        myFixture.configureByText("trigger.ts", "")
        val screen = PsiTreeUtil.findChildrenOfType(moduleFile("a/Screen.tsx"), com.intellij.psi.PsiNameIdentifierOwner::class.java)
            .first { it.name == "Screen" }
        assertFalse("file-based walk alone misses the alias importer",
            analyzer().isLive(moduleFile("a/Screen.tsx"), "Screen"))
        assertTrue("symbol search must find the alias importer",
            analyzer().hasExternalSymbolConsumer(screen))
    }

    // A symbol used only inside its own module has no EXTERNAL consumer -> backstop says false,
    // so it does not mask a genuinely-unused export.
    fun testSymbolConsumerIgnoresSameFileUse() {
        myFixture.addFileToProject("m.ts",
            "export const helper = () => 1\nexport const Main = () => helper()\n")
        myFixture.configureByText("trigger.ts", "")
        val helper = PsiTreeUtil.findChildrenOfType(moduleFile("m.ts"), com.intellij.psi.PsiNameIdentifierOwner::class.java)
            .first { it.name == "helper" }
        assertFalse("same-file-only use must not count as an external consumer",
            analyzer().hasExternalSymbolConsumer(helper))
    }

    // A symbol forwarded only by a dead barrel (the forwarding link resolves to it but no one
    // imports it) must NOT be kept alive: re-export sites are classified out.
    fun testSymbolConsumerIgnoresReExportForwarding() {
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen.tsx'\n")
        myFixture.configureByText("trigger.ts", "")
        val screen = PsiTreeUtil.findChildrenOfType(moduleFile("a/Screen.tsx"), com.intellij.psi.PsiNameIdentifierOwner::class.java)
            .first { it.name == "Screen" }
        assertFalse("a bare re-export forwarding is not a real consumer",
            analyzer().hasExternalSymbolConsumer(screen))
    }

    // Isolate just the explicit-extension factor: does ReferencesSearch on the file return a
    // re-export whose from-clause carries the '.tsx' extension?
    fun testLeafLiveThroughExtensionedReExport() {
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen.tsx'\n")
        myFixture.addFileToProject("use.ts", "import { Screen } from './a'\nconst x = Screen\n")
        myFixture.configureByText("trigger.ts", "")
        assertTrue("leaf reached via '.tsx'-extensioned re-export must be live",
            analyzer().isLive(moduleFile("a/Screen.tsx"), "Screen"))
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

    private fun exportStarDeclMatching(file: com.intellij.psi.PsiFile, fromContains: String): ES6ExportDeclaration =
        PsiTreeUtil.findChildrenOfType(file, ES6ExportDeclaration::class.java)
            .first { it.isExportAll && (it.fromClause?.referenceText ?: "").contains(fromContains) }

    fun testExportStarLiveViaNamedImport() {
        // Regression for the reported false positive: a barrel re-exports a component via
        // `export *` and the ONLY consumer uses a NAMED import. The source module exports the
        // imported name, so the wildcard must be live.
        myFixture.addFileToProject("c/AvatarInput.tsx", "export const AvatarInput = () => null\n")
        myFixture.addFileToProject("c/index.ts", "export * from './AvatarInput'\n")
        myFixture.addFileToProject("use.ts", "import { AvatarInput } from './c'\nconst x = AvatarInput\n")
        myFixture.configureByText("trigger.ts", "")
        val barrel = moduleFile("c/index.ts")
        assertTrue("export * must be live when a named import draws a name the source exports",
            analyzer().isExportStarLive(barrel, exportStarDeclMatching(barrel, "AvatarInput")))
    }

    fun testExportStarDeadAmongLiveBarrel() {
        // The reported false NEGATIVE: a barrel forwards two modules via `export *`; consumers
        // import only `Used`. `export * from './SomeFun'` exports nothing anyone imports, so it
        // must be dead EVEN THOUGH the barrel itself is live via `Used`.
        myFixture.addFileToProject("c/Used.tsx", "export const Used = 1\n")
        myFixture.addFileToProject("c/SomeFun.ts", "export const SomeFun = 2\n")
        myFixture.addFileToProject("c/index.ts", "export * from './Used'\nexport * from './SomeFun'\n")
        myFixture.addFileToProject("use.ts", "import { Used } from './c'\nconst x = Used\n")
        myFixture.configureByText("trigger.ts", "")
        val barrel = moduleFile("c/index.ts")
        val az = analyzer()
        assertTrue("export * from './Used' is consumed -> live",
            az.isExportStarLive(barrel, exportStarDeclMatching(barrel, "Used")))
        assertFalse("export * from './SomeFun' exports nothing imported -> dead",
            az.isExportStarLive(barrel, exportStarDeclMatching(barrel, "SomeFun")))
    }

    fun testExportStarDeadWithNoConsumer() {
        // No real consumer at all (only a side-effect import, which consumes nothing) -> dead.
        myFixture.addFileToProject("d/AvatarInput.tsx", "export const AvatarInput = () => null\n")
        myFixture.addFileToProject("d/index.ts", "export * from './AvatarInput'\n")
        myFixture.addFileToProject("use.ts", "import './d'\n")
        myFixture.configureByText("trigger.ts", "")
        val barrel = moduleFile("d/index.ts")
        assertFalse("export * with no name-consuming consumer must be dead",
            analyzer().isExportStarLive(barrel, exportStarDeclMatching(barrel, "AvatarInput")))
    }

    fun testExportStarLiveViaNamespaceImport() {
        // A namespace import takes the whole barrel namespace -> the wildcard is live.
        myFixture.addFileToProject("e/AvatarInput.tsx", "export const AvatarInput = () => null\n")
        myFixture.addFileToProject("e/index.ts", "export * from './AvatarInput'\n")
        myFixture.addFileToProject("use.ts", "import * as ns from './e'\nconst x = ns\n")
        myFixture.configureByText("trigger.ts", "")
        val barrel = moduleFile("e/index.ts")
        assertTrue("namespace import keeps the wildcard live",
            analyzer().isExportStarLive(barrel, exportStarDeclMatching(barrel, "AvatarInput")))
    }

    fun testExportStarLiveThroughWildcardReExportChain() {
        // Chain: AvatarInput/SomeFun -> barrel (`export *`) -> agg (`export * from barrel`) ->
        // consumer imports `Used` from agg. The wildcard re-export site in agg must be followed
        // so `export * from './Used'` is live, while `export * from './SomeFun'` (nothing agg's
        // consumer draws comes from SomeFun) is dead — even reached only transitively via agg.
        myFixture.addFileToProject("c/Used.tsx", "export const Used = 1\n")
        myFixture.addFileToProject("c/SomeFun.ts", "export const SomeFun = 2\n")
        myFixture.addFileToProject("c/index.ts", "export * from './Used'\nexport * from './SomeFun'\n")
        myFixture.addFileToProject("agg.ts", "export * from './c'\n")               // wildcard re-export site
        myFixture.addFileToProject("use.ts", "import { Used } from './agg'\nconst x = Used\n")
        myFixture.configureByText("trigger.ts", "")
        val barrel = moduleFile("c/index.ts")
        val az = analyzer()
        assertTrue("export * from './Used' is live through the wildcard chain",
            az.isExportStarLive(barrel, exportStarDeclMatching(barrel, "Used")))
        assertFalse("export * from './SomeFun' is dead through the wildcard chain",
            az.isExportStarLive(barrel, exportStarDeclMatching(barrel, "SomeFun")))
    }

    fun testExportStarLiveThroughNamedReExportChain() {
        // Chain: barrel forwards Used + SomeFun via `export *`; agg NAMED-re-exports one of the
        // barrel's wildcard names under an alias (`export { Used as U } from './c'`); a consumer
        // imports the alias `U`. The named re-export site must map the alias back to the source
        // name `Used` and resolve it against each `export *`'s source module: live for './Used',
        // dead for './SomeFun' (which never exports `Used`).
        myFixture.addFileToProject("c/Used.tsx", "export const Used = 1\n")
        myFixture.addFileToProject("c/SomeFun.ts", "export const SomeFun = 2\n")
        myFixture.addFileToProject("c/index.ts", "export * from './Used'\nexport * from './SomeFun'\n")
        myFixture.addFileToProject("agg.ts", "export { Used as U } from './c'\n")    // named re-export site
        myFixture.addFileToProject("use.ts", "import { U } from './agg'\nconst x = U\n")
        myFixture.configureByText("trigger.ts", "")
        val barrel = moduleFile("c/index.ts")
        val az = analyzer()
        assertTrue("export * from './Used' is live: agg forwards Used as U and U is imported",
            az.isExportStarLive(barrel, exportStarDeclMatching(barrel, "Used")))
        assertFalse("export * from './SomeFun' is dead: SomeFun never exports the forwarded 'Used'",
            az.isExportStarLive(barrel, exportStarDeclMatching(barrel, "SomeFun")))
    }

    fun testPartialBarrelOnlyImportedNameLive() {
        // barrel forwards two names; the only consumer imports just one of them.
        myFixture.addFileToProject("b/x.ts", "export const Live = 1\nexport const Dead = 2\n")
        myFixture.addFileToProject("b/index.ts", "export { Live, Dead } from './x'\n")
        myFixture.addFileToProject("use.ts", "import { Live } from './b'\nconst y = Live\n")
        myFixture.configureByText("trigger.ts", "")
        val az = analyzer() // ONE shared analyzer across both queries
        assertTrue("Live is imported -> live", az.isLive(moduleFile("b/index.ts"), "Live"))
        assertFalse("Dead is never imported -> dead", az.isLive(moduleFile("b/index.ts"), "Dead"))
    }

    fun testAliasedImportConsumesSourceName() {
        // `import { Dead as D }` consumes the SOURCE name Dead, not the alias D.
        myFixture.addFileToProject("b/x.ts", "export const Live = 1\nexport const Dead = 2\n")
        myFixture.addFileToProject("b/index.ts", "export { Live, Dead } from './x'\n")
        myFixture.addFileToProject("use.ts", "import { Dead as D } from './b'\nconst y = D\n")
        myFixture.configureByText("trigger.ts", "")
        val az = analyzer() // ONE shared analyzer across both queries
        assertTrue("Dead is imported (as D) -> live", az.isLive(moduleFile("b/index.ts"), "Dead"))
        assertFalse("Live is never imported -> dead", az.isLive(moduleFile("b/index.ts"), "Live"))
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
