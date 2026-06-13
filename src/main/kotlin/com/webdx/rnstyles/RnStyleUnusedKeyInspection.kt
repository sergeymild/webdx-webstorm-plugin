package com.webdx.rnstyles

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.webdx.cssmodules.CssModules

/**
 * Greys a key declared in a `StyleSheet.create({...})` object that is never referenced
 * (`<binding>.<key>` or via destructuring) within its scope — the containing file for an
 * inline object, the importer files for an exported one.
 */
class RnStyleUnusedKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        if (!CssModules.isJsLikeFileName(file.name)) return PsiElementVisitor.EMPTY_VISITOR

        val localObjects = RnStyles.fileStyleSheets(file).values.toSet()
        if (localObjects.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR

        val usedByObj = HashMap<JSObjectLiteralExpression, Set<String>>()
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is JSProperty) return
                val obj = element.parent as? JSObjectLiteralExpression ?: return
                if (obj !in localObjects) return
                val name = element.name ?: return
                val used = usedByObj.getOrPut(obj) { RnStyles.collectUsedKeys(obj) }
                if (name !in used) {
                    val anchor = element.nameIdentifier ?: element
                    holder.registerProblem(
                        anchor,
                        "Style '$name' is not used",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    )
                }
            }
        }
    }
}
