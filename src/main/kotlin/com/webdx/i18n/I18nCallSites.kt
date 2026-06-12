package com.webdx.i18n

import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue

/** Recognizes the string literals that hold an i18n key, shared by reference + inspection. */
internal object I18nCallSites {

    private val CALLEES = setOf("t", "i18next.t", "i18n.t")

    /**
     * The key text if [element] holds an i18n key, else null. Handles two hosts:
     *  - the first string argument of `t(...)` / `i18next.t(...)` / `i18n.t(...)`;
     *  - the value of a `<Trans i18nKey="...">` attribute (`XmlAttributeValue`).
     * Only plain string literals match (template literals are a different PSI type),
     * and only in files that import i18n (kills false positives on other `t`s).
     */
    fun keyOf(element: PsiElement): String? {
        val key = when (element) {
            is JSLiteralExpression -> keyOfCallArgument(element)
            is XmlAttributeValue -> keyOfTransAttribute(element)
            else -> null
        } ?: return null
        return if (fileImportsI18n(element)) key else null
    }

    private fun keyOfCallArgument(literal: JSLiteralExpression): String? {
        val value = literal.stringValue ?: return null // not a plain string literal
        val argList = literal.parent as? JSArgumentList ?: return null
        if (argList.arguments.firstOrNull() !== literal) return null // only the key argument
        val call = argList.parent as? JSCallExpression ?: return null
        val callee = call.methodExpression?.text?.trim() ?: return null
        return if (callee in CALLEES) value else null
    }

    private fun keyOfTransAttribute(value: XmlAttributeValue): String? {
        val attr = value.parent as? XmlAttribute ?: return null
        return if (attr.name == "i18nKey") value.value else null
    }

    private fun fileImportsI18n(element: PsiElement): Boolean {
        val text = element.containingFile?.text ?: return false
        return text.contains("i18next") || text.contains("react-i18next")
    }
}
