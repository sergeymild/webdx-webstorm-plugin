package com.webdx.i18n

import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The shared predicate "is this string literal an i18n key, and what is it?",
 * used by both the reference contributor and the unknown-key inspection.
 */
class I18nCallSitesTest : BasePlatformTestCase() {

    private val imports = "import { useTranslation } from 'react-i18next';\n"

    /** Configure [body] (with a `<caret>` inside the string) and run the matcher there. */
    private fun keyAt(body: String): String? {
        myFixture.configureByText("C.tsx", imports + body)
        return matcherAtCaret()
    }

    /** The key host at the caret is either a JS string literal or a JSX attribute value. */
    private fun matcherAtCaret(): String? {
        val el = myFixture.file.findElementAt(myFixture.caretOffset)
        // A JSX element is itself a JSLiteralExpression subtype, so prefer the
        // attribute value when present.
        val host: PsiElement? = PsiTreeUtil.getParentOfType(el, XmlAttributeValue::class.java, false)
            ?: PsiTreeUtil.getParentOfType(el, JSLiteralExpression::class.java, false)
        return host?.let { I18nCallSites.keyOf(it) }
    }

    fun testMatchesTCallArgument() {
        assertEquals("common.action.copy", keyAt("const x = t('common.action.co<caret>py');"))
    }

    fun testMatchesI18nextDotT() {
        assertEquals("page.title", keyAt("const x = i18next.t('page.ti<caret>tle');"))
    }

    fun testMatchesI18nDotT() {
        assertEquals("page.title", keyAt("const x = i18n.t('page.ti<caret>tle');"))
    }

    fun testMatchesTransI18nKey() {
        assertEquals("page.title", keyAt("const x = <Trans i18nKey='page.ti<caret>tle' />;"))
    }

    fun testIgnoresUnrelatedCall() {
        assertNull(keyAt("const x = format('page.ti<caret>tle');"))
    }

    fun testIgnoresSecondArgumentOfT() {
        assertNull(keyAt("const x = t('page.title', 'fallb<caret>ack');"))
    }

    fun testIgnoresWhenFileHasNoI18nImport() {
        myFixture.configureByText("C.tsx", "const x = t('page.ti<caret>tle');")
        assertNull(matcherAtCaret())
    }
}
