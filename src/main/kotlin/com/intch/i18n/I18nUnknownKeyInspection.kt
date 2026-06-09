package com.intch.i18n

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/** Warns when a `t('...')` / `<Trans i18nKey>` key is not present in the locale JSON. */
class I18nUnknownKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val key = I18nCallSites.keyOf(element) ?: return
                val keys = I18nKeyIndex.keys(element.project)
                if (keys.isEmpty()) return // no key source located -> stay silent
                if (key !in keys) {
                    holder.registerProblem(
                        element,
                        "Unknown translation key '$key'",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    )
                }
            }
        }
    }
}
