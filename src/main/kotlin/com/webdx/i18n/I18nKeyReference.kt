package com.webdx.i18n

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.util.ProcessingContext

/**
 * A reference from a `t('...')` / `<Trans i18nKey>` key string to its definition in
 * the locale JSON. Powers go-to-definition, find-usages, and completion (getVariants).
 */
class I18nKeyReference(
    element: PsiElement,
    range: TextRange,
    private val key: String,
) : PsiReferenceBase<PsiElement>(element, range) {

    override fun resolve(): PsiElement? = I18nKeyIndex.resolve(element.project, key)

    override fun getVariants(): Array<Any> =
        I18nKeyIndex.keys(element.project)
            .map { LookupElementBuilder.create(it) }
            .toTypedArray()
}

/** Attaches [I18nKeyReference] to the recognized key literals / JSX attribute values. */
class I18nKeyReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        val provider = object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val key = I18nCallSites.keyOf(element) ?: return PsiReference.EMPTY_ARRAY
                // The key text sits inside the surrounding quotes (single char each side).
                val end = (element.textLength - 1).coerceAtLeast(1)
                return arrayOf(I18nKeyReference(element, TextRange(1, end), key))
            }
        }
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(JSLiteralExpression::class.java), provider)
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlAttributeValue::class.java), provider)

        // Option-object key (`{ price: … }`) -> the {{price}} placeholder in the value.
        val placeholderProvider = object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val property = element as? JSProperty ?: return PsiReference.EMPTY_ARRAY
                val name = property.name ?: return PsiReference.EMPTY_ARRAY
                val key = I18nOptions.keyForOptionProperty(property) ?: return PsiReference.EMPTY_ARRAY
                val project = element.project
                if (name !in I18nKeyIndex.placeholders(project, key)) return PsiReference.EMPTY_ARRAY
                val valueLiteral = I18nKeyIndex.resolve(project, key)?.value as? JsonStringLiteral
                    ?: return PsiReference.EMPTY_ARRAY
                val nameId = property.nameIdentifier ?: return PsiReference.EMPTY_ARRAY
                val range = nameId.textRange.shiftLeft(property.textRange.startOffset)
                return arrayOf(I18nPlaceholderReference(property, range, valueLiteral, name))
            }
        }
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(JSProperty::class.java), placeholderProvider)
    }
}
