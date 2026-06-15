package com.webdx.cssmodules

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

        // Nothing consumes this module from JS (directly or via @import chain) -> can't tell what's used.
        val used = CssModules.collectUsedClassNames(file) ?: return PsiElementVisitor.EMPTY_VISITOR

        val bamDecls = BamSelectors.bamClassDeclarations(file) // subjectName -> selector element
        val bamElementToName = bamDecls.entries.associate { (name, el) -> el to name }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is CssClass) {
                    val name = element.name?.removePrefix(".")?.takeIf(String::isNotEmpty) ?: return
                    flagIfUnused(element, name)
                    return
                }
                bamElementToName[element]?.let { name -> flagIfUnused(element, name) }
            }

            private fun flagIfUnused(element: PsiElement, name: String) {
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
