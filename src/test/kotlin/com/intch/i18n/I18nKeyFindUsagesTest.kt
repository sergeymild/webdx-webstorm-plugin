package com.intch.i18n

import com.intellij.json.psi.JsonProperty
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Find Usages on a translation-key property in a locale JSON lists ONLY the
 * t('key')/<Trans i18nKey> references that resolve to that exact key — not other
 * properties with the same name, not the same key in sibling locale files.
 */
class I18nKeyFindUsagesTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "src/lang/translations/en.json",
            """{ "page": { "timer": "Reserved for:", "other": "Other" } }""",
        )
        // Sibling locale with the SAME key + same-named property — must NOT appear.
        myFixture.addFileToProject(
            "src/lang/translations/de.json",
            """{ "page": { "timer": "Reserviert:", "other": "Andere" } }""",
        )
        myFixture.addFileToProject(
            "src/lang/initLocales.ts",
            "import { initReactI18next } from 'react-i18next'\nimport en from './translations/en.json'",
        )
        myFixture.addFileToProject(
            "Use.tsx",
            "import { useTranslation } from 'react-i18next';\n" +
                "const a = t('page.timer');\n" +
                "const b = t('page.other');\n",
        )
    }

    fun testUsagesScopedToTheKey() {
        val prop = I18nKeyIndex.resolve(project, "page.timer") as JsonProperty
        val usages = myFixture.findUsages(prop)

        // Exactly the one t('page.timer') reference; no JSON properties, no 'page.other'.
        assertEquals("usages: ${usages.map { it.element?.text }}", 1, usages.size)
        val text = usages.first().element?.text ?: ""
        assertTrue("got: $text", text.contains("page.timer"))
        assertTrue(usages.none { it.element is JsonProperty })
    }

    fun testUsagesFromSiblingLocaleResolveToTheSameCode() {
        val deVf = myFixture.findFileInTempDir("src/lang/translations/de.json")
        val dePsi = com.intellij.psi.PsiManager.getInstance(project).findFile(deVf)!!
        val deProp = I18nKeys.resolveProperty(dePsi, "page.timer")!!
        val usages = myFixture.findUsages(deProp)

        assertEquals("usages: ${usages.map { it.element?.text }}", 1, usages.size)
        assertTrue(usages.first().element!!.text.contains("page.timer"))
    }
}
