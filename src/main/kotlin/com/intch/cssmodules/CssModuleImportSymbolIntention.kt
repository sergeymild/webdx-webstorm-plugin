package com.intch.cssmodules

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Alt+Enter intention on a SCSS symbol usage (`@include mixin`, a `@function` call,
 * `$variable`, `%placeholder`) that resolves only by name because its defining file
 * isn't imported: offers to add `@import '<alias>';` for that file. The path uses the
 * project's `@/` tsconfig alias when available, else a relative path.
 */
class CssModuleImportSymbolIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName(): String = "Add @import for SCSS symbol"

    override fun getText(): String = "Add @import for this SCSS symbol"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        if (editor == null) return false
        val file = element.containingFile?.originalFile ?: return false
        if (!isScssFile(file.name)) return false
        val name = symbolNameAt(element) ?: return false
        val def = CssModules.scssSymbolIndex(project)[name] ?: return false
        if (def == file.virtualFile) return false // we're in the defining file itself
        return !CssModules.importsTarget(file, def)
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        if (editor == null) return
        val file = element.containingFile?.originalFile ?: return
        val name = symbolNameAt(element) ?: return
        val def = CssModules.scssSymbolIndex(project)[name] ?: return
        val fromDir = file.virtualFile?.parent ?: return
        val specifier = CssModules.importSpecifierFor(project, fromDir, def) ?: return

        val doc = editor.document
        val already = Regex("""@(?:import|use|forward)\s+['"]${Regex.escape(specifier)}['"]""")
        if (already.containsMatchIn(doc.text)) return
        doc.insertString(0, "@import '$specifier';\n")
        PsiDocumentManager.getInstance(project).commitDocument(doc)
    }

    /** The bare symbol name under the caret (leading `$`/`%` stripped), or null. */
    private fun symbolNameAt(element: PsiElement): String? {
        val raw = element.text?.trim()?.trimStart('$', '%') ?: return null
        return raw.takeIf { it.isNotEmpty() && IDENTIFIER.matches(it) }
    }

    private fun isScssFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".scss") || n.endsWith(".sass")
    }

    private companion object {
        private val IDENTIFIER = Regex("""[A-Za-z_][\w-]*""")
    }
}
