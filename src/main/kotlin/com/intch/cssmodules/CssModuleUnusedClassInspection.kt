package com.intch.cssmodules

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.css.CssClass

/**
 * Flags class selectors in a `*.module.scss|css|less` file that are never
 * referenced as `<binding>.<class>` in any file importing that module.
 */
class CssModuleUnusedClassInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        if (!CssModules.isModuleFileName(file.name)) return PsiElementVisitor.EMPTY_VISITOR

        val importers = CssModules.findImporters(file)
        // No importer yet -> we can't tell what's used; don't cry wolf.
        if (importers.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR

        val used = CssModules.collectUsedClassNames(file)

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is CssClass) return
                val name = element.name?.removePrefix(".")?.takeIf(String::isNotEmpty) ?: return
                if (name !in used) {
                    holder.registerProblem(
                        element,
                        "CSS module class '$name' is not used in importing files",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    )
                }
            }
        }
    }
}
