package com.intch.i18n

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The index ties detection + flattening together for the reference and inspection. */
class I18nKeyIndexTest : BasePlatformTestCase() {

    private fun setUpProject() {
        myFixture.addFileToProject(
            "src/lang/translations/en.json",
            """{ "common": { "action": { "copy": "Copy" } }, "page": { "title": "T" } }""",
        )
        myFixture.addFileToProject(
            "src/lang/initLocales.ts",
            "import { initReactI18next } from 'react-i18next'\nimport en from './translations/en.json'",
        )
    }

    fun testExposesKeysAndResolves() {
        setUpProject()
        val keys = I18nKeyIndex.keys(project)
        assertTrue("got: $keys", keys.containsAll(listOf("common.action.copy", "page.title")))
        assertNotNull(I18nKeyIndex.resolve(project, "common.action.copy"))
        assertNull(I18nKeyIndex.resolve(project, "common.action.missing"))
    }

    fun testEmptyWhenNoSource() {
        myFixture.addFileToProject("src/index.ts", "export const x = 1")
        assertTrue(I18nKeyIndex.keys(project).isEmpty())
        assertNull(I18nKeyIndex.resolve(project, "anything"))
    }
}
