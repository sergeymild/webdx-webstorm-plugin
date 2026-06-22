package com.webdx.barrels

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BarrelExportsPathTest : BasePlatformTestCase() {

    fun testIndexFilePrefersTs() {
        myFixture.addFileToProject("comp/index.tsx", "")
        val dir = myFixture.addFileToProject("comp/a.ts", "").virtualFile.parent
        assertEquals("index.tsx", BarrelExports.indexFileIn(dir)?.name)
    }

    fun testIndexFileNullWhenAbsent() {
        val dir = myFixture.addFileToProject("comp/a.ts", "").virtualFile.parent
        assertNull(BarrelExports.indexFileIn(dir))
    }

    fun testSourceRootFromTsconfigBaseUrlRoot() {
        myFixture.addFileToProject("tsconfig.json", """{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
        val dir = myFixture.addFileToProject("src/feature/a.ts", "").virtualFile.parent
        // baseUrl unset -> tsconfig dir (project root). Its name is the temp root dir.
        val root = BarrelExports.sourceRoot(dir, project)
        assertNotNull(root)
        assertNotNull(root!!.findChild("tsconfig.json"))
    }

    fun testIsModuleRootByPackageJson() {
        myFixture.addFileToProject("packages/ui/package.json", """{ "name": "ui" }""")
        val dir = myFixture.addFileToProject("packages/ui/a.ts", "").virtualFile.parent
        assertTrue(BarrelExports.isModuleRoot(dir, project))
    }

    fun testIsModuleRootByAliasTarget() {
        myFixture.addFileToProject(
            "tsconfig.json",
            """{ "compilerOptions": { "paths": { "@mod": ["./src/mod/index.ts"] } } }""",
        )
        myFixture.addFileToProject("src/mod/index.ts", "")
        val dir = myFixture.addFileToProject("src/mod/a.ts", "").virtualFile.parent
        assertTrue(BarrelExports.isModuleRoot(dir, project))
    }

    fun testIsModuleRootFalseForPlainDir() {
        val dir = myFixture.addFileToProject("src/plain/a.ts", "").virtualFile.parent
        assertFalse(BarrelExports.isModuleRoot(dir, project))
    }
}
