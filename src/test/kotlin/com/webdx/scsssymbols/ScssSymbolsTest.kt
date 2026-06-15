package com.webdx.scsssymbols

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssSymbolsTest : BasePlatformTestCase() {

    fun testDeclarationsInAllKinds() {
        val scss = myFixture.addFileToProject(
            "vars.scss",
            "\$base: 8px;\n@function calcSize(\$f) { @return \$f; }\n@mixin safe { padding: 0; }\n%card { border: 0; }",
        )
        val decls = ScssSymbols.declarationsIn(scss)
        val byName = decls.associateBy { it.name }
        assertEquals(ScssSymbols.Kind.VARIABLE, byName["base"]!!.kind)
        assertEquals(ScssSymbols.Kind.FUNCTION, byName["calcSize"]!!.kind)
        assertEquals(ScssSymbols.Kind.MIXIN, byName["safe"]!!.kind)
        assertEquals(ScssSymbols.Kind.PLACEHOLDER, byName["card"]!!.kind)
        // the element is the name token at the declaration site
        assertTrue(byName["base"]!!.element.text.contains("base"))
    }

    fun testDeclarationsIgnoreReferences() {
        val scss = myFixture.addFileToProject(
            "f.scss",
            "\$a: 1;\n.x { width: \$a; @include foo; }",
        )
        // `$a` used in `.x` is NOT a second declaration; `foo` include is not a declaration
        assertEquals(listOf("a"), ScssSymbols.declarationsIn(scss).map { it.name })
    }
}
