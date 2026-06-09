package com.intch.i18n

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.FakePsiElement

/**
 * A reference from an option-object key (`t('k', { price: … })`) to the matching
 * `{{price}}` placeholder inside the key's value in the locale JSON. Resolving it
 * (Cmd+Click) navigates into the JSON with the caret on the placeholder.
 */
class I18nPlaceholderReference(
    element: PsiElement,
    range: TextRange,
    private val valueLiteral: JsonStringLiteral,
    private val name: String,
) : PsiReferenceBase<PsiElement>(element, range) {

    override fun resolve(): PsiElement? {
        val literalText = valueLiteral.text
        val match = Regex("""\{\{\s*${Regex.escape(name)}""").find(literalText)
            ?: return valueLiteral // fall back to the value start if not found
        val nameStartInLiteral = match.range.first + (match.value.length - name.length)
        val absolute = valueLiteral.textRange.startOffset + nameStartInLiteral
        return PlaceholderTarget(valueLiteral, absolute)
    }
}

/** A zero-width navigation target at [absoluteOffset] inside the locale JSON file. */
private class PlaceholderTarget(
    private val anchor: JsonStringLiteral,
    private val absoluteOffset: Int,
) : FakePsiElement() {

    override fun getParent(): PsiElement = anchor
    override fun getContainingFile(): PsiFile = anchor.containingFile
    override fun getTextRange(): TextRange = TextRange(absoluteOffset, absoluteOffset)
    override fun getTextOffset(): Int = absoluteOffset
    override fun getNavigationElement(): PsiElement = this
    override fun isValid(): Boolean = anchor.isValid
}
