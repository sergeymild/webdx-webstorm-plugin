package com.webdx.barrels

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BarrelExportsChainTest : BasePlatformTestCase() {

    private fun tsconfig(body: String) = myFixture.addFileToProject("tsconfig.json", body)

    fun testChainIntchEveryLevelStopsAtHighestIndex() {
        tsconfig("""{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './Button'\n")
        myFixture.addFileToProject("components/Button/index.ts", "export * from './Button'\n")
        val dir = myFixture.addFileToProject("components/Button/Button.tsx", "export const Button = 1\n").virtualFile.parent
        val chain = BarrelExports.barrelChain(dir, project).map { it.name }
        assertEquals(listOf("Button", "components"), chain)
    }

    fun testChainMuseSingleModuleBarrelViaAlias() {
        tsconfig("""{ "compilerOptions": { "baseUrl": ".", "paths": { "@m/score": ["./src/screens/score/index.ts"] } } }""")
        myFixture.addFileToProject("package.json", """{ "name": "rn" }""")
        myFixture.addFileToProject("src/screens/score/index.ts", "export { A } from './A'\n")
        val dir = myFixture.addFileToProject(
            "src/screens/score/components/modals/Sheet.tsx",
            "export const Sheet = 1\n",
        ).virtualFile.parent
        val chain = BarrelExports.barrelChain(dir, project).map { it.name }
        assertEquals(listOf("score"), chain)
    }

    fun testRelativeSpecifierSameDirFileStripsExtension() {
        val file = myFixture.addFileToProject("components/Button/Button.tsx", "").virtualFile
        val dir = file.parent
        assertEquals("./Button", BarrelExports.relativeSpecifier(dir, file))
    }

    fun testRelativeSpecifierMultiSegmentToFile() {
        myFixture.addFileToProject("m/index.ts", "")
        val file = myFixture.addFileToProject("m/components/modals/Sheet.tsx", "").virtualFile
        val dir = file.parent.parent.parent // "m"
        assertEquals("./components/modals/Sheet", BarrelExports.relativeSpecifier(dir, file))
    }

    fun testRelativeSpecifierToChildDir() {
        myFixture.addFileToProject("containers/index.ts", "")
        val child = myFixture.addFileToProject("containers/Touchpoint/index.ts", "").virtualFile.parent
        val dir = child.parent // "containers"
        assertEquals("./Touchpoint", BarrelExports.relativeSpecifier(dir, child))
    }
}
