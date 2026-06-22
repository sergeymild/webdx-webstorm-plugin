package com.webdx.barrels

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

class BarrelExportIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName(): String = "Export through barrel modules"
    override fun getText(): String = "Export through barrel modules"
    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean = false
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {}
}
