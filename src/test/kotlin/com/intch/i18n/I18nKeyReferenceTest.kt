package com.intch.i18n

import com.intellij.json.psi.JsonProperty
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Functional: go-to-definition and key completion via the reference contributor. */
class I18nKeyReferenceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "src/lang/translations/en.json",
            """{ "common": { "action": { "copy": "Copy", "save": "Save" } }, "page": { "title": "T" } }""",
        )
        myFixture.addFileToProject(
            "src/lang/initLocales.ts",
            "import { initReactI18next } from 'react-i18next'\nimport en from './translations/en.json'",
        )
    }

    fun testReferenceResolvesToJsonProperty() {
        myFixture.configureByText(
            "C.tsx",
            "import { useTranslation } from 'react-i18next';\nconst x = t('common.action.co<caret>py');",
        )
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("no reference at caret", ref)
        val resolved = ref!!.resolve()
        assertTrue("resolved to ${resolved?.javaClass}", resolved is JsonProperty)
        assertEquals("copy", (resolved as JsonProperty).name)
    }

    fun testTransReferenceResolves() {
        myFixture.configureByText(
            "C.tsx",
            "import { Trans } from 'react-i18next';\nconst x = <Trans i18nKey='page.ti<caret>tle' />;",
        )
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("no reference at caret", ref)
        assertTrue(ref!!.resolve() is JsonProperty)
    }

    fun testCompletionOffersKeys() {
        myFixture.configureByText(
            "C.tsx",
            "import { useTranslation } from 'react-i18next';\nconst x = t('common.action.<caret>');",
        )
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected key variants, got: $items", items.contains("common.action.copy"))
    }
}
