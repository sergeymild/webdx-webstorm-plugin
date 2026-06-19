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

        // subjectName -> every declaring selector element; invert to flag EACH site of an
        // unused bam class (a class is often declared at several `&__x` / `#{$var}__x` sites).
        val bamElementToName = HashMap<PsiElement, String>()
        for ((name, elements) in BamSelectors.bamClassDeclarations(file)) {
            for (element in elements) bamElementToName[element] = name
        }

        // `.name` inside `@extend .name` is parsed as a CssClass but is a REFERENCE to a class
        // declared elsewhere — never a declaration to flag here.
        val extendRanges = CssModules.extendClassRefRanges(file.text)

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is CssClass) {
                    if (CssModules.rangeOverlapsAny(element.textRange, extendRanges)) return
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
