package com.webdx.scsssymbols

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * Cmd+Click / Cmd+B on a SCSS symbol, resolved through the import graph:
 *  - on a USE (`$x` / `ns.$x` / `@include name` / `func(` / `@extend %ph`) → the declaration;
 *  - on a DECLARATION (`$x:` / `@function`/`@mixin`/`%ph`) → its usages (one navigates
 *    directly, many show the platform's target-chooser popup).
 * The standard EP works for `.scss` (the TS-Go fork only intercepts `.tsx`).
 */
class ScssSymbolGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        if (element.firstChild != null) return null // leaves only

        // Use → declaration.
        ScssSymbols.referenceAt(element)?.let { ref ->
            return ScssSymbols.resolve(ref)?.let { arrayOf(it.element) }
        }

        // Declaration → usages (so Cmd+Click on the declaration jumps to where it's used).
        val decl = declAt(element) ?: return null
        val project = element.project
        val vf = decl.file.virtualFile ?: return null
        val locs = ScssSymbols.usesByDeclaration(project)[DeclKey(vf, decl.name, decl.kind)] ?: return null
        val psiManager = PsiManager.getInstance(project)
        val targets = locs.mapNotNull { psiManager.findFile(it.file)?.findElementAt(it.offset) }
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /** The declaration whose name-token contains [element]'s offset, or null. */
    private fun declAt(element: PsiElement): ScssSymbols.Decl? {
        val file = element.containingFile?.originalFile ?: return null
        val offset = element.textOffset
        return ScssSymbols.declarationsIn(file).firstOrNull { it.element.textRange.contains(offset) }
    }
}
