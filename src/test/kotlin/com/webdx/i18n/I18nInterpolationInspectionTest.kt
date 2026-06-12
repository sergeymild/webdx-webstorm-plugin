package com.webdx.i18n

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Functional: validate the t(key, {options}) interpolation object against the key's placeholders. */
class I18nInterpolationInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "src/lang/translations/en.json",
            """
            {
              "page": { "step_label": "Step {{step}}", "title": "Title" },
              "spec": "We have {{specialistsCount}} specialists",
              "items": "{{count}} items"
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/lang/initLocales.ts",
            """
            import { initReactI18next } from 'react-i18next'
            import en from './translations/en.json'
            const c = { interpolation: { defaultVariables: { specialistsCount: 1, specialistsCountFull: 2 } } }
            """.trimIndent(),
        )
        myFixture.enableInspections(I18nInterpolationInspection())
    }

    private fun warnings(prefix: String): List<String> =
        myFixture.doHighlighting().mapNotNull { it.description }.filter { it.startsWith(prefix) }

    private fun configure(body: String) =
        myFixture.configureByText("C.tsx", "import { useTranslation } from 'react-i18next';\n$body")

    fun testUnknownOptionKey() {
        configure("const x = t('page.step_label', { stp: 1 });")
        assertTrue(warnings("Unknown interpolation variable 'stp'").isNotEmpty())
    }

    fun testMissingPlaceholderWithEmptyObject() {
        configure("const x = t('page.step_label', {});")
        assertTrue(warnings("Missing interpolation variable 'step'").isNotEmpty())
    }

    fun testMissingOptionsObjectEntirely() {
        configure("const x = t('page.step_label');")
        assertTrue(warnings("Missing interpolation options").isNotEmpty())
    }

    fun testValidOptionsNoWarnings() {
        configure("const x = t('page.step_label', { step: 1 });")
        assertTrue(warnings("Unknown interpolation").isEmpty())
        assertTrue(warnings("Missing interpolation").isEmpty())
    }

    fun testSpreadSuppressesMissing() {
        configure("const rest = { step: 1 };\nconst x = t('page.step_label', { ...rest });")
        assertTrue(warnings("Missing interpolation").isEmpty())
    }

    fun testDefaultVariableNotRequired() {
        configure("const x = t('spec');")
        assertTrue(warnings("Missing interpolation").isEmpty())
    }

    fun testReservedCountSatisfiesAndIsAllowed() {
        configure("const x = t('items', { count: 2 });")
        assertTrue(warnings("Unknown interpolation").isEmpty())
        assertTrue(warnings("Missing interpolation").isEmpty())
    }

    fun testNonObjectSecondArgIsIgnored() {
        configure("const opts = { step: 1 };\nconst x = t('page.step_label', opts);")
        assertTrue(warnings("Missing interpolation").isEmpty())
        assertTrue(warnings("Unknown interpolation").isEmpty())
    }
}
