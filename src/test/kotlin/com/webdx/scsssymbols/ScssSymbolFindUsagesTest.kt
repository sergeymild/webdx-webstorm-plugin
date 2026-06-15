package com.webdx.scsssymbols

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssSymbolFindUsagesTest : BasePlatformTestCase() {

    fun testFindsVariableUsagesAcrossFiles() {
        val vars = myFixture.addFileToProject("vars.scss", "\$brand: red;")
        myFixture.addFileToProject("a.scss", "@use './vars.scss' as v;\n.a { color: v.\$brand; }")
        myFixture.addFileToProject("b.scss", "@import './vars.scss';\n.b { color: \$brand; border-color: \$brand; }")
        // caret on the `$brand` declaration
        val offset = vars.text.indexOf("\$brand") + 1
        val element = vars.findElementAt(offset)!!
        val usages = myFixture.findUsages(element)
        // v.$brand (a.scss) + 2× $brand (b.scss) = 3
        assertEquals("usages: ${usages.map { it.element?.containingFile?.name }}", 3, usages.size)
    }

    fun testDoesNotMatchSameNamedSymbolResolvingElsewhere() {
        val vars = myFixture.addFileToProject("vars.scss", "\$x: 1;")
        // other.scss declares its OWN $x and uses it locally — must NOT count for vars.scss's $x
        myFixture.addFileToProject("other.scss", "\$x: 2;\n.o { width: \$x; }")
        val offset = vars.text.indexOf("\$x") + 1
        val usages = myFixture.findUsages(vars.findElementAt(offset)!!)
        assertEquals("usages: ${usages.map { it.element?.containingFile?.name }}", 0, usages.size)
    }
}
