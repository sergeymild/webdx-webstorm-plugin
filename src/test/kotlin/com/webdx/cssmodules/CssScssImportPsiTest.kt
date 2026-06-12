package com.webdx.cssmodules

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** PSI tests for SCSS import resolution + transitive class collection + the import graph. */
class CssScssImportPsiTest : BasePlatformTestCase() {

    // --- resolveImportPath -------------------------------------------------

    fun testResolvesRelativeSibling() {
        val common = myFixture.addFileToProject("src/a/common.module.scss", ".x {}")
        val from = myFixture.addFileToProject("src/a/Comp.module.scss", "")
        val dir = from.virtualFile.parent
        val resolved = CssModules.resolveImportPath(dir, project, "./common.module.scss")
        assertEquals(common.virtualFile, resolved)
    }

    fun testResolvesRelativeParent() {
        val common = myFixture.addFileToProject("src/common.module.scss", ".x {}")
        val from = myFixture.addFileToProject("src/a/Comp.module.scss", "")
        val dir = from.virtualFile.parent
        val resolved = CssModules.resolveImportPath(dir, project, "../common.module.scss")
        assertEquals(common.virtualFile, resolved)
    }

    fun testResolvesAtAlias() {
        myFixture.addFileToProject("tsconfig.json", """{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
        val common = myFixture.addFileToProject("src/containers/Onboarding/common.module.scss", ".x {}")
        val from = myFixture.addFileToProject("src/containers/Onboarding/sub/Comp.module.scss", "")
        val dir = from.virtualFile.parent
        val resolved = CssModules.resolveImportPath(dir, project, "@/src/containers/Onboarding/common.module.scss")
        assertEquals(common.virtualFile, resolved)
    }

    fun testResolvesAtAliasWithBaseUrl() {
        myFixture.addFileToProject(
            "tsconfig.json",
            """{ "compilerOptions": { "baseUrl": ".", "paths": { "@/*": ["src/*"] } } }""",
        )
        val common = myFixture.addFileToProject("src/shared/common.module.scss", ".x {}")
        val from = myFixture.addFileToProject("src/feat/Comp.module.scss", "")
        val dir = from.virtualFile.parent
        val resolved = CssModules.resolveImportPath(dir, project, "@/shared/common.module.scss")
        assertEquals(common.virtualFile, resolved)
    }

    fun testUnresolvableReturnsNull() {
        val from = myFixture.addFileToProject("src/Comp.module.scss", "")
        val dir = from.virtualFile.parent
        assertNull(CssModules.resolveImportPath(dir, project, "@/does/not/exist.module.scss"))
        assertNull(CssModules.resolveImportPath(dir, project, "./nope.module.scss"))
    }

    // --- collectAllClassNames ---------------------------------------------

    fun testCollectAllIncludesImportedClasses() {
        myFixture.addFileToProject("src/common.module.scss", ".nextButton {} .note {}")
        val mod = myFixture.addFileToProject(
            "src/StepGraph.module.scss",
            "@import \"./common.module.scss\";\n.container {}",
        )
        assertEquals(setOf("container", "nextButton", "note"), CssModules.collectAllClassNames(mod).toSet())
    }

    fun testCollectAllIsTransitive() {
        myFixture.addFileToProject("src/c.module.scss", ".cc {}")
        myFixture.addFileToProject("src/b.module.scss", "@import \"./c.module.scss\";\n.bb {}")
        val a = myFixture.addFileToProject("src/a.module.scss", "@import \"./b.module.scss\";\n.aa {}")
        assertEquals(setOf("aa", "bb", "cc"), CssModules.collectAllClassNames(a).toSet())
    }

    fun testCollectAllSurvivesCycles() {
        myFixture.addFileToProject("src/a.module.scss", "@import \"./b.module.scss\";\n.aa {}")
        val b = myFixture.addFileToProject("src/b.module.scss", "@import \"./a.module.scss\";\n.bb {}")
        assertEquals(setOf("aa", "bb"), CssModules.collectAllClassNames(b).toSet())
    }

    fun testCollectAllIgnoresNonModuleImports() {
        myFixture.addFileToProject("src/vars.scss", ".globalThing {}")
        val mod = myFixture.addFileToProject(
            "src/Comp.module.scss",
            "@import \"./vars.scss\";\n.local {}",
        )
        assertEquals(setOf("local"), CssModules.collectAllClassNames(mod).toSet())
    }

    // --- modulesTransitivelyImporting -------------------------------------

    fun testReverseReachabilityFindsDirectAndTransitiveImporters() {
        val common = myFixture.addFileToProject("src/common.module.scss", ".x {}")
        val mid = myFixture.addFileToProject("src/Mid.module.scss", "@import './common.module.scss';\n.m {}")
        val top = myFixture.addFileToProject("src/Top.module.scss", "@import './Mid.module.scss';\n.t {}")
        myFixture.addFileToProject("src/Unrelated.module.scss", ".u {}")

        val importers = CssModules.modulesTransitivelyImporting(common)
        assertEquals(
            setOf(common.virtualFile, mid.virtualFile, top.virtualFile),
            importers,
        )
    }

    fun testReverseReachabilityIsJustSelfWhenNobodyImports() {
        val lonely = myFixture.addFileToProject("src/Lonely.module.scss", ".x {}")
        assertEquals(setOf(lonely.virtualFile), CssModules.modulesTransitivelyImporting(lonely))
    }

    // --- importedClassOrigins (override detection) ------------------------

    fun testImportedClassOriginsExcludesOwnAndMapsToSource() {
        myFixture.addFileToProject("src/common.module.scss", ".nextButton {} .note {}")
        val mod = myFixture.addFileToProject(
            "src/Comp.module.scss",
            "@import './common.module.scss';\n.nextButton {}\n.local {}",
        )
        val origins = CssModules.importedClassOrigins(mod)
        // Only imported classes appear (nextButton, note); the own-only `local` does not.
        assertEquals(setOf("nextButton", "note"), origins.keys)
        assertEquals("common.module.scss", origins["nextButton"]?.name)
    }

    fun testImportedClassOriginsEmptyWithoutImports() {
        val mod = myFixture.addFileToProject("src/Solo.module.scss", ".a {} .b {}")
        assertTrue(CssModules.importedClassOrigins(mod).isEmpty())
    }
}
