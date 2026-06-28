package com.webdx.i18n

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/** Validates the `t(key, { options })` interpolation object against the key's `{{placeholders}}`. */
class I18nInterpolationInspection : LocalInspectionTool() {

    override fun getStaticDescription(): String =
        "An unknown or missing interpolation option for a t('key', { ... }) call."

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is JSLiteralExpression) return
                val key = I18nCallSites.keyOf(element) ?: return // import guard + matching
                val call = I18nOptions.tCallOf(element) ?: return // call form only (not <Trans>)

                val project = element.project
                val keys = I18nKeyIndex.keys(project)
                if (keys.isEmpty() || key !in keys) return // unknown key handled by the other inspection

                val placeholders = I18nKeyIndex.placeholders(project, key)
                val defaults = I18nKeyIndex.defaultVariables(project)
                val required = placeholders - defaults

                val secondArg = call.arguments.getOrNull(1)
                if (secondArg == null) {
                    if (required.isNotEmpty()) {
                        holder.registerProblem(
                            element,
                            "Missing interpolation options for '$key': ${required.sorted().joinToString(", ")}",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        )
                    }
                    return
                }
                val obj = secondArg as? JSObjectLiteralExpression ?: return // non-literal: can't verify

                val allowed = placeholders + defaults + I18nOptions.RESERVED
                val present = mutableSetOf<String>()
                var dynamic = obj.text.contains("...") // spread -> can't know what it provides
                for (prop in obj.properties) {
                    val name = prop.name
                    if (name == null) {
                        dynamic = true // computed key
                        continue
                    }
                    present += name
                    if (name !in allowed) {
                        holder.registerProblem(
                            prop,
                            "Unknown interpolation variable '$name' for key '$key'",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        )
                    }
                }
                if (!dynamic) {
                    for (missing in (required - present)) {
                        holder.registerProblem(
                            obj,
                            "Missing interpolation variable '$missing'",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        )
                    }
                }
            }
        }
    }
}
