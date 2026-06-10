package com.intch.cssmodules

import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssClass
import com.intellij.psi.util.PsiTreeUtil

/**
 * Shared resolution for `styles.<class>` -> the single effective CSS class
 * declaration. When the importing module redefines an `@import`-ed class, the
 * local declaration (the Sass-cascade winner) is returned; otherwise the file in
 * the import chain that declares it. Resolved from source PSI only (no TS service),
 * via [CssModules.collectClassOrigins] (own file wins on a name clash).
 *
 * Used by both [CssModuleDirectNavigationProvider] (the Ctrl+Click / modern path,
 * which short-circuits the TS service's symbol navigation) and
 * [CssModuleGotoDeclarationHandler] (the legacy Go to Declaration path).
 */
internal object CssModuleClassNavigation {

    /** [element] is the leaf under the caret. Returns the target CssClass or null. */
    fun resolveTarget(element: PsiElement): PsiElement? {
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

        return PsiTreeUtil.collectElementsOfType(declaringFile, CssClass::class.java)
            .firstOrNull { it.name?.removePrefix(".") == name }
    }

    /** True when [element] is a `<identifier> . <identifier>` member-access leaf (cheap shape check for logging). */
    fun isMemberAccessLeaf(element: PsiElement): Boolean {
        if (element.firstChild != null) return false
        val name = element.text
        if (name.isEmpty() || !name.first().isJavaIdentifierStart()) return false
        return CssModules.prevMeaningfulLeaf(element)?.text == "."
    }
}
