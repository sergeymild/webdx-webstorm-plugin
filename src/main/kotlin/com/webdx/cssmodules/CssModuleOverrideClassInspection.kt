package com.webdx.cssmodules

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.css.CssClass

/**
 * Warns on a class selector in a `*.module.scss|css|sass|less` file whose name is
 * also declared in a module it (transitively) `@import`s — i.e. a local override of
 * an imported class. Sass inlines the imported rule, so both apply and the local
 * one wins the cascade; surfacing it helps catch unintended shadowing.
 */
class CssModuleOverrideClassInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        if (!CssModules.isModuleFileName(file.name)) return PsiElementVisitor.EMPTY_VISITOR

        val importedOrigins = CssModules.importedClassOrigins(file)
        if (importedOrigins.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is CssClass) return
                val name = element.name?.removePrefix(".")?.takeIf(String::isNotEmpty) ?: return
                val source = importedOrigins[name] ?: return
                holder.registerProblem(
                    element,
                    "CSS module class '$name' overrides a class from imported '${source.name}'",
                    ProblemHighlightType.WARNING,
                )
            }
        }
    }
}
