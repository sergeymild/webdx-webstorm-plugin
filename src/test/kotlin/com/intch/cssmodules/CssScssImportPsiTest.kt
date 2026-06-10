package com.intch.cssmodules

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

    fun testUnresolvableReturnsNull() {
        val from = myFixture.addFileToProject("src/Comp.module.scss", "")
        val dir = from.virtualFile.parent
        assertNull(CssModules.resolveImportPath(dir, project, "@/does/not/exist.module.scss"))
        assertNull(CssModules.resolveImportPath(dir, project, "./nope.module.scss"))
    }
}
