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

    fun testReferencesInAllForms() {
        val scss = myFixture.addFileToProject(
            "f.scss",
            "\$a: 1;\n.x {\n  width: \$a;\n  height: v.\$b;\n  margin: calcSize(\$a);\n  @include safe;\n  @include ns.safe2;\n  @extend %card;\n}",
        )
        val refs = ScssSymbols.referencesIn(scss)
        fun has(name: String, kind: ScssSymbols.Kind, ns: String?) =
            refs.any { it.name == name && it.kind == kind && it.namespace == ns }
        assertTrue("bare var", has("a", ScssSymbols.Kind.VARIABLE, null))
        assertTrue("ns var", has("b", ScssSymbols.Kind.VARIABLE, "v"))
        assertTrue("function call", has("calcSize", ScssSymbols.Kind.FUNCTION, null))
        assertTrue("mixin include", has("safe", ScssSymbols.Kind.MIXIN, null))
        assertTrue("ns mixin", has("safe2", ScssSymbols.Kind.MIXIN, "ns"))
        assertTrue("placeholder extend", has("card", ScssSymbols.Kind.PLACEHOLDER, null))
        // the `$a:` declaration LHS is excluded; only the two usages (`width: $a`,
        // `calcSize($a)`) count as references — without the exclusion this would be 3.
        assertEquals(
            "decl LHS must be excluded; only usages counted",
            2,
            refs.count { it.kind == ScssSymbols.Kind.VARIABLE && it.name == "a" },
        )
    }

    fun testReferenceAtClassifiesCaret() {
        val scss = myFixture.addFileToProject("f.scss", "\$a: 1;\n.x { width: \$a; }")
        val offset = scss.text.lastIndexOf("\$a") + 1 // inside the usage `$a`
        val ref = ScssSymbols.referenceAt(scss.findElementAt(offset)!!)
        assertEquals("a", ref!!.name)
        assertEquals(ScssSymbols.Kind.VARIABLE, ref.kind)
    }
}
