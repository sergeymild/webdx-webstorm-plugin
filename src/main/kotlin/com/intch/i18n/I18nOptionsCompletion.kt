package com.intch.i18n

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.psi.util.PsiTreeUtil

/** Inside `t('key', { <caret> })`, completes the key's `{{placeholder}}` names. */
class I18nOptionsCompletion : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val obj = PsiTreeUtil.getParentOfType(position, JSObjectLiteralExpression::class.java) ?: return
        val argList = obj.parent as? JSArgumentList ?: return
        if (argList.arguments.getOrNull(1) !== obj) return
        val keyLiteral = argList.arguments.getOrNull(0) as? JSLiteralExpression ?: return
        val key = I18nCallSites.keyOf(keyLiteral) ?: return

        val placeholders = I18nKeyIndex.placeholders(parameters.editor.project ?: return, key)
        val present = obj.properties.mapNotNull { it.name }.toSet()
        for (name in placeholders - present) {
            val element = LookupElementBuilder.create(name).withTypeText("i18n variable", true)
            result.addElement(PrioritizedLookupElement.withPriority(element, 100.0))
        }
    }
}
