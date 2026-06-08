package com.intch.cssmodules

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Flags `styles.<name>` member accesses where `<name>` is not a class defined in
 * the imported CSS module (`import styles from './X.module.scss'`).
 */
class CssModuleUnknownClassInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        if (!CssModules.isJsLikeFileName(file.name)) return PsiElementVisitor.EMPTY_VISITOR

        val bindings = CssModules.cssModuleBindings(file)
        if (bindings.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.firstChild != null) return // leaves only
                val name = element.text
                if (name.isEmpty() || !name.first().isJavaIdentifierStart()) return

                val dot = CssModules.prevMeaningfulLeaf(element) ?: return
                if (dot.text != ".") return
                val qualifier = CssModules.prevMeaningfulLeaf(dot) ?: return
                val classes = bindings[qualifier.text] ?: return // not a CSS-module binding

                if (name !in classes) {
                    holder.registerProblem(
                        element,
                        "Unknown CSS module class '$name'",
                        ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
                    )
                }
            }
        }
    }
}
