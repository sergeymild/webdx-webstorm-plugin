package com.webdx.scsssymbols

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssSymbolGotoDeclarationTest : BasePlatformTestCase() {

    private val handler = ScssSymbolGotoDeclarationHandler()

    private fun targetFileAtCaret(): String? {
        val element = myFixture.file.findElementAt(myFixture.caretOffset)
        return handler.getGotoDeclarationTargets(element, myFixture.caretOffset, myFixture.editor)
            ?.firstOrNull()?.containingFile?.name
    }

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
}
