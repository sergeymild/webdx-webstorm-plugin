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
}
