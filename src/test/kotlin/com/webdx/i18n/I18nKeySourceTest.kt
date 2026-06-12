package com.webdx.i18n

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Locating the key-source JSON in a project: config-driven, with a convention fallback. */
class I18nKeySourceTest : BasePlatformTestCase() {

    fun testFindsKeySourceByFollowingEnImport() {
        myFixture.addFileToProject("src/lang/translations/en.json", "{}")
        myFixture.addFileToProject("src/lang/translations/ru.json", "{}")
        myFixture.addFileToProject(
            "src/lang/initLocales.ts",
            """
            import { initReactI18next } from 'react-i18next'
            import en from './translations/en.json'
            import ru from './translations/ru.json'
            """.trimIndent(),
        )
        val vf = I18nConfig.findKeySource(project)
        assertNotNull("expected to locate the key source", vf)
        assertTrue("got: ${vf?.path}", vf!!.path.endsWith("src/lang/translations/en.json"))
    }

    fun testFallsBackToConventionWhenNoConfig() {
        myFixture.addFileToProject("whatever/translations/en.json", "{}")
        val vf = I18nConfig.findKeySource(project)
        assertEquals("en.json", vf?.name)
    }

    fun testReturnsNullWhenNoEnJsonAnywhere() {
        myFixture.addFileToProject("src/index.ts", "export const x = 1")
        assertNull(I18nConfig.findKeySource(project))
    }
}
