package com.intch.i18n

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Index exposes a key's placeholders and the config's auto-provided default variables. */
class I18nInterpolationIndexTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "src/lang/translations/en.json",
            """{ "page": { "step_label": "Step {{step}}", "title": "Title" } }""",
        )
        myFixture.addFileToProject(
            "src/lang/initLocales.ts",
            """
            import { initReactI18next } from 'react-i18next'
            import en from './translations/en.json'
            const x = {
              interpolation: {
                defaultVariables: { specialistsCount: 1, specialistsCountFull: 2 },
              },
            }
            """.trimIndent(),
        )
    }

    fun testPlaceholdersOfKey() {
        assertEquals(setOf("step"), I18nKeyIndex.placeholders(project, "page.step_label"))
        assertEquals(emptySet<String>(), I18nKeyIndex.placeholders(project, "page.title"))
        assertEquals(emptySet<String>(), I18nKeyIndex.placeholders(project, "page.unknown"))
    }

    fun testDefaultVariablesFromConfig() {
        assertEquals(
            setOf("specialistsCount", "specialistsCountFull"),
            I18nKeyIndex.defaultVariables(project),
        )
    }
}
