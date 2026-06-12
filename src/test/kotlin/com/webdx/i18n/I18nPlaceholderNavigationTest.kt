package com.webdx.i18n

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Cmd+Click on an option-object key (`t('k', { price: … })`) navigates into the
 * locale JSON and lands the caret on that `{{placeholder}}` inside the key's value.
 */
class I18nPlaceholderNavigationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "src/lang/translations/en.json",
            """{ "page": { "disc": "Pay {{price}} now", "plain": "no vars" } }""",
        )
        myFixture.addFileToProject(
            "src/lang/initLocales.ts",
            "import { initReactI18next } from 'react-i18next'\nimport en from './translations/en.json'",
        )
    }

    private fun refAtCaret(body: String) = run {
        myFixture.configureByText("C.tsx", "import { useTranslation } from 'react-i18next';\n$body")
        myFixture.file.findReferenceAt(myFixture.caretOffset)
    }

    fun testNavigatesToPlaceholderOffset() {
        val ref = refAtCaret("const x = t('page.disc', { pri<caret>ce: 1 });")
        assertNotNull("no reference on the option key", ref)
        val target = ref!!.resolve()
        assertNotNull("did not resolve", target)
        assertEquals("en.json", target!!.containingFile?.name)
        val expected = target.containingFile!!.text.indexOf("{{price}}") + "{{".length
        assertEquals("caret should land at the placeholder name", expected, target.textOffset)
    }

    /** A non-placeholder option key must not navigate into the locale JSON. */
    private fun assertDoesNotNavigateToLocale(body: String) {
        val target = refAtCaret(body)?.resolve()
        assertTrue("should not navigate into en.json", target == null || target.containingFile?.name != "en.json")
    }

    fun testNoReferenceWhenKeyNotAPlaceholder() {
        // `count` is a reserved option but not a placeholder of "disc".
        assertDoesNotNavigateToLocale("const x = t('page.disc', { cou<caret>nt: 1 });")
    }

    fun testNoReferenceForKeyWithoutPlaceholders() {
        assertDoesNotNavigateToLocale("const x = t('page.plain', { fo<caret>o: 1 });")
    }
}
