package com.intch.i18n

import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression

/** Helpers for the `t(key, { options })` interpolation object. */
internal object I18nOptions {

    private val CALLEES = setOf("t", "i18next.t", "i18n.t")

    /**
     * Reserved i18next `t` options that are always allowed in the options object,
     * so they are never flagged as unknown interpolation variables.
     */
    val RESERVED = setOf(
        "count", "context", "ordinal", "defaultValue", "ns", "lng", "lngs",
        "replace", "interpolation", "keySeparator", "nsSeparator",
        "returnObjects", "returnDetails", "postProcess", "fallbackLng",
        "formatParams", "joinArrays", "skipOnVariables",
    )

    /** The `t(...)` / `i18next.t(...)` / `i18n.t(...)` call whose first arg is [literal], else null. */
    fun tCallOf(literal: JSLiteralExpression): JSCallExpression? {
        val argList = literal.parent as? JSArgumentList ?: return null
        if (argList.arguments.firstOrNull() !== literal) return null
        val call = argList.parent as? JSCallExpression ?: return null
        val callee = call.methodExpression?.text?.trim() ?: return null
        return if (callee in CALLEES) call else null
    }
}
