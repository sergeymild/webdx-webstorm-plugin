package com.webdx.barrels

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.webdx.cssmodules.CssModules

/**
 * Alt+Enter on an exported top-level symbol: re-export it through every existing index.ts(x) barrel
 * up the tree, to the auto-detected module root. Writes only barrels (never consumer files), never
 * creates missing index files, matches each target file's style, and applies all edits as one
 * undoable command. Delegates all logic to [BarrelExports].
 */
class BarrelExportIntention : PsiElementBaseIntentionAction() {

    @Volatile private var label: String? = null

    override fun getFamilyName(): String = "Export through barrel modules"

    override fun getText(): String = "Export through barrels up to ${label ?: "module"}"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val file = element.containingFile?.originalFile ?: return false
        val name = file.name
        if (!CssModules.isJsLikeFileName(name)) return false
        if (name.substringBeforeLast('.').equals("index", ignoreCase = true)) return false
        val (symbol, isDefault) = BarrelExports.exportedNameAt(element) ?: return false
        val plan = BarrelExports.planFor(file, symbol, isDefault, project) ?: return false
        label = plan.moduleRootLabel
        return plan.edits.isNotEmpty()
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val file = element.containingFile?.originalFile ?: return
        val (symbol, isDefault) = BarrelExports.exportedNameAt(element) ?: return
        val plan = BarrelExports.planFor(file, symbol, isDefault, project) ?: return
        val pdm = PsiDocumentManager.getInstance(project)
        WriteCommandAction.runWriteCommandAction(project, getFamilyName(), null, {
            for (edit in plan.edits) {
                val doc = FileDocumentManager.getInstance().getDocument(edit.indexFile) ?: continue
                val cur = doc.text
                val prefix = if (cur.isEmpty() || cur.endsWith("\n")) "" else "\n"
                doc.insertString(doc.textLength, prefix + edit.line + "\n")
                pdm.commitDocument(doc)
            }
        })
    }
}
