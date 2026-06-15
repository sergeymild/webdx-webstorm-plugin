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

    fun testCmdClickOnDeclarationGoesToUsages() {
        // Cmd+Click on a declaration should jump to its usages (one → direct, many → popup).
        // Caret sits INSIDE the variable name token (as a real click would land).
        myFixture.addFileToProject("a.scss", "@import './vars.scss';\n.a { z-index: \$zNavHeader; }")
        myFixture.addFileToProject("b.scss", "@import './vars.scss';\n.b { z-index: \$zNavHeader; }")
        myFixture.configureByText("vars.scss", "\$zNav<caret>Header: 1000;")
        val targets = targetsAtCaret()
        assertNotNull("expected usage targets from the declaration", targets)
        assertEquals(2, targets!!.size)
        assertEquals(setOf("a.scss", "b.scss"), targets.mapNotNull { it.containingFile?.name }.toSet())
    }

    fun testNoUsageTargetsForUnusedDeclaration() {
        myFixture.configureByText("vars.scss", "\$dea<caret>d: 1;")
        assertNull(targetsAtCaret())
    }
}
