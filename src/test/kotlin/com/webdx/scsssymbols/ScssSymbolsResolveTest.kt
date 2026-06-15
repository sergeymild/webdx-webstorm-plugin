package com.webdx.scsssymbols

import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssSymbolsResolveTest : BasePlatformTestCase() {

    fun testResolvesNamespacedAndBareAcrossFiles() {
        val vars = myFixture.addFileToProject("vars.scss", "\$x: 1;\n@mixin safe { padding: 0; }")
        val f = myFixture.addFileToProject(
            "f.scss",
            "@use './vars.scss' as v;\n@import './vars.scss';\n.a { width: v.\$x; height: \$x; @include safe; }",
        )
        val refs = ScssSymbols.referencesIn(f)
        val nsVar = refs.first { it.name == "x" && it.namespace == "v" }
        val bareVar = refs.first { it.name == "x" && it.namespace == null }
        val mixin = refs.first { it.kind == ScssSymbols.Kind.MIXIN }
        assertEquals("vars.scss", ScssSymbols.resolve(nsVar)!!.file.name)
        assertEquals("vars.scss", ScssSymbols.resolve(bareVar)!!.file.name)
        assertEquals("vars.scss", ScssSymbols.resolve(mixin)!!.file.name)
    }

    fun testResolvePrefersLocalOnCollision() {
        myFixture.addFileToProject("vars.scss", "\$x: 1;")
        val f = myFixture.addFileToProject("f.scss", "@import './vars.scss';\n\$x: 2;\n.a { width: \$x; }")
        val use = ScssSymbols.referencesIn(f).first { it.name == "x" }
        // local `$x` (in f.scss) wins over the imported one
        assertEquals("f.scss", ScssSymbols.resolve(use)!!.file.name)
    }

    fun testUsesByDeclarationGroupsRefs() {
        val vars = myFixture.addFileToProject("vars.scss", "\$used: 1;\n\$dead: 2;")
        myFixture.addFileToProject("f.scss", "@import './vars.scss';\n.a { width: \$used; height: \$used; }")
        val uses = ScssSymbols.usesByDeclaration(project)
        val usedKey = ScssSymbols.declKey(vars.virtualFile, "used", ScssSymbols.Kind.VARIABLE)
        val deadKey = ScssSymbols.declKey(vars.virtualFile, "dead", ScssSymbols.Kind.VARIABLE)
        assertEquals(2, uses[usedKey]?.size ?: 0)
        assertNull(uses[deadKey])
    }
}
