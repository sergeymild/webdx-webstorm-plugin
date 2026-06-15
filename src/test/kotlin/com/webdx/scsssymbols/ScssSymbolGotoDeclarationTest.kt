package com.webdx.scsssymbols

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssSymbolGotoDeclarationTest : BasePlatformTestCase() {

    private val handler = ScssSymbolGotoDeclarationHandler()

    private fun targetsAtCaret(): Array<com.intellij.psi.PsiElement>? {
        val element = myFixture.file.findElementAt(myFixture.caretOffset)
        return handler.getGotoDeclarationTargets(element, myFixture.caretOffset, myFixture.editor)
    }

    private fun targetFileAtCaret(): String? = targetsAtCaret()?.firstOrNull()?.containingFile?.name

    fun testGoesToVariableDeclarationAcrossFiles() {
        myFixture.addFileToProject("vars.scss", "\$brand: red;")
        myFixture.configureByText("f.scss", "@use './vars.scss' as v;\n.a { color: v.\$bra<caret>nd; }")
        assertEquals("vars.scss", targetFileAtCaret())
    }

    fun testGoesToMixinDeclaration() {
        myFixture.addFileToProject("mix.scss", "@mixin safe { padding: 0; }")
        myFixture.configureByText("f.scss", "@import './mix.scss';\n.a { @include sa<caret>fe; }")
        assertEquals("mix.scss", targetFileAtCaret())
    }

    fun testNoTargetForUnknownSymbol() {
        myFixture.configureByText("f.scss", ".a { color: \$noth<caret>ing; }")
        assertNull(targetFileAtCaret())
    }

    // Cmd+Click on a DECLARATION → "Show Usages" is decided by `isDeclarationAt` (the action
    // override triggers the native Show Usages popup, which isn't fixture-testable). The
    // handler itself must NOT return usage targets for a declaration (that rendered as a
    // contextless "Choose Declaration" list).

    fun testHandlerReturnsNoTargetsOnDeclaration() {
        myFixture.addFileToProject("a.scss", "@import './vars.scss';\n.a { z-index: \$zNavHeader; }")
        myFixture.configureByText("vars.scss", "\$zNav<caret>Header: 1000;")
        assertNull("handler must not turn a declaration into go-to targets", targetsAtCaret())
    }

    fun testIsDeclarationAtDistinguishesDeclFromUse() {
        myFixture.addFileToProject("vars.scss", "\$brand: red;")
        // caret on a USE → not a declaration
        myFixture.configureByText("use.scss", "@import './vars.scss';\n.a { color: \$bra<caret>nd; }")
        val useEl = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertFalse(com.webdx.scsssymbols.ScssSymbols.isDeclarationAt(useEl))

        // caret on the DECLARATION → is a declaration
        myFixture.configureByText("vars.scss", "\$bra<caret>nd: red;")
        val declEl = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertTrue(com.webdx.scsssymbols.ScssSymbols.isDeclarationAt(declEl))
    }
}
