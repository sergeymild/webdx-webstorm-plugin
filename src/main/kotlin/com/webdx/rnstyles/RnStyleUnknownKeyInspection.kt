package com.webdx.rnstyles

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.webdx.cssmodules.CssModules

/**
 * Flags `<binding>.<key>` accesses where `<binding>` resolves to a `StyleSheet.create`
 * object and `<key>` is not one of its style keys. Only fires when the binding resolves
 * to a StyleSheet object, so unrelated member access is never redlined.
 */
class RnStyleUnknownKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        if (!CssModules.isJsLikeFileName(file.name)) return PsiElementVisitor.EMPTY_VISITOR

        val bindings = RnStyles.bindingsInFile(file).mapValues { RnStyles.styleKeys(it.value).toSet() }
        if (bindings.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.firstChild != null) return
                val name = element.text
                if (name.isEmpty() || !name.first().isJavaIdentifierStart()) return
                val dot = CssModules.prevMeaningfulLeaf(element) ?: return
                if (dot.text != ".") return
                val qualifier = CssModules.prevMeaningfulLeaf(dot) ?: return
                // Skip chained access (e.g. `theme.styles.key`): the qualifier is itself a member.
                val dotBeforeQ = CssModules.prevMeaningfulLeaf(qualifier)
                if (dotBeforeQ != null && dotBeforeQ.text == ".") return
                val keys = bindings[qualifier.text] ?: return
                if (name !in keys) {
                    holder.registerProblem(
                        element,
                        "Unknown style key '$name'",
                        ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
                    )
                }
            }
        }
    }
}
