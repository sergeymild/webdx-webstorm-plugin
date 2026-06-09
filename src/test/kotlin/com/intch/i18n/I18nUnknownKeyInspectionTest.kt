package com.intch.i18n

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Functional: the inspection flags unknown keys and stays quiet on valid/dynamic ones. */
class I18nUnknownKeyInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "src/lang/translations/en.json",
            """{ "common": { "action": { "copy": "Copy" } }, "page": { "title": "T" } }""",
        )
        myFixture.addFileToProject(
            "src/lang/initLocales.ts",
            "import { initReactI18next } from 'react-i18next'\nimport en from './translations/en.json'",
        )
        myFixture.enableInspections(I18nUnknownKeyInspection())
    }

    private fun unknownKeyWarnings(): List<String> {
        return myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it.startsWith("Unknown translation key") }
    }

    fun testFlagsUnknownKey() {
        myFixture.configureByText(
            "C.tsx",
            "import { useTranslation } from 'react-i18next';\nconst x = t('common.action.missing');",
        )
        assertEquals(listOf("Unknown translation key 'common.action.missing'"), unknownKeyWarnings())
    }

    fun testNoWarningForKnownKey() {
        myFixture.configureByText(
            "C.tsx",
            "import { useTranslation } from 'react-i18next';\nconst x = t('common.action.copy');",
        )
        assertTrue(unknownKeyWarnings().isEmpty())
    }

    fun testIgnoresDynamicTemplateLiteral() {
        myFixture.configureByText(
            "C.tsx",
            "import { useTranslation } from 'react-i18next';\nconst i = 1;\nconst x = t(`page.steps.${'$'}{i}`);",
        )
        assertTrue(unknownKeyWarnings().isEmpty())
    }

    fun testFlagsUnknownTransKey() {
        myFixture.configureByText(
            "C.tsx",
            "import { Trans } from 'react-i18next';\nconst x = <Trans i18nKey='nope.nope' />;",
        )
        assertEquals(listOf("Unknown translation key 'nope.nope'"), unknownKeyWarnings())
    }
}
