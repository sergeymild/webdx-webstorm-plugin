package com.webdx.scsssymbols

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/**
 * Go-to-declaration on a SCSS symbol use (`$x` / `ns.$x` / `@include name` / `func(` /
 * `@extend %ph`) → the declaration, resolved through the import graph. The standard EP
 * works for `.scss` (the TS-Go fork only intercepts `.tsx`), so no action override.
 */
class ScssSymbolGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        if (element.firstChild != null) return null // leaves only
        val ref = ScssSymbols.referenceAt(element) ?: return null
        val target = ScssSymbols.resolve(ref) ?: return null
        return arrayOf(target.element)
    }
}
