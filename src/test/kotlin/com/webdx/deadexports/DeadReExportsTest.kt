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
}
