package com.intch.i18n

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Functional: completing the options object offers the key's placeholder names. */
class I18nOptionsCompletionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "src/lang/translations/en.json",
            """{ "multi": "{{first}} and {{second}}", "plain": "no vars" }""",
        )
        myFixture.addFileToProject(
            "src/lang/initLocales.ts",
            "import { initReactI18next } from 'react-i18next'\nimport en from './translations/en.json'",
        )
    }

    private fun complete(body: String): List<String> {
        myFixture.configureByText("C.tsx", "import { useTranslation } from 'react-i18next';\n$body")
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    fun testOffersPlaceholders() {
        val items = complete("const x = t('multi', { <caret> });")
        assertTrue("got: $items", items.containsAll(listOf("first", "second")))
    }

    fun testDoesNotOfferAlreadyPresent() {
        val items = complete("const x = t('multi', { first: 1, <caret> });")
        assertTrue("expected 'second', got: $items", items.contains("second"))
        assertFalse("should not re-offer 'first', got: $items", items.contains("first"))
    }

    fun testNoPlaceholdersOffersNothingOfOurs() {
        val items = complete("const x = t('plain', { <caret> });")
        assertFalse(items.contains("first"))
        assertFalse(items.contains("second"))
    }
}
