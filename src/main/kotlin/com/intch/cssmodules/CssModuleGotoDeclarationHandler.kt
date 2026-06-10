package com.intch.cssmodules

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssClass
import com.intellij.psi.util.PsiTreeUtil

/**
 * Go-to-declaration for `styles.<class>` that points at the SINGLE effective
 * declaration of the class. When the class is redefined in the importing module
 * itself (a local override of an `@import`-ed class), navigation lands on the
 * local declaration — the one whose properties win the Sass cascade — instead of
 * the imported source. Otherwise it lands on the file that actually declares it.
 *
 * Resolution is from source / generic PSI only (no TypeScript service), via
 * [CssModules.collectClassOrigins] (own file wins on a name clash).
 *
 * NOTE: the platform merges targets from every go-to provider; if the TS service
 * also resolves `styles.<class>`, its targets are added alongside this one.
 */
class CssModuleGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        if (element.firstChild != null) return null // leaves only
        val file = element.containingFile ?: return null
        if (!CssModules.isJsLikeFileName(file.name)) return null

        val name = element.text
        if (name.isEmpty() || !name.first().isJavaIdentifierStart()) return null

        val dot = CssModules.prevMeaningfulLeaf(element) ?: return null
        if (dot.text != ".") return null
        val qualifier = CssModules.prevMeaningfulLeaf(dot) ?: return null
        val binding = qualifier.text
        if (binding.isEmpty() || !binding.first().isJavaIdentifierStart()) return null

        val moduleFile = CssModules.resolveModuleForBinding(file, binding) ?: return null
        val declaringFile = CssModules.collectClassOrigins(moduleFile)[name] ?: return null
        val cssClass = PsiTreeUtil.collectElementsOfType(declaringFile, CssClass::class.java)
            .firstOrNull { it.name?.removePrefix(".") == name } ?: return null

        return arrayOf(cssClass)
    }
}
