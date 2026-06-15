package com.webdx.scsssymbols

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/**
 * Go-to-declaration on a SCSS symbol USE (`$x` / `ns.$x` / `@include name` / `func(` /
 * `@extend %ph`) → the declaration, resolved through the import graph. The standard EP
 * works for `.scss` (the TS-Go fork only intercepts `.tsx`).
 *
 * The reverse direction (Cmd+Click on a DECLARATION → its usages, shown as the native
 * "Show Usages" popup with code preview) is handled in [com.webdx.cssmodules.CssModuleGotoDeclarationAction],
 * which runs for both Cmd+B and Cmd+Click — returning many raw usage tokens as go-to
 * targets here would render as a contextless "Choose Declaration" list, which is unhelpful.
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
