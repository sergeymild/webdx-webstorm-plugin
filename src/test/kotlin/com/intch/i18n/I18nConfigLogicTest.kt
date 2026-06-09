package com.intch.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure logic: given the text of the file that wires up i18n, find the path of
 * the JSON imported under the local name `en` (the canonical key source).
 */
class I18nConfigLogicTest {

    @Test
    fun findsEnImportPath() {
        val text = """
            import i18n from 'i18next'
            import { initReactI18next } from 'react-i18next'
            import en from './translations/en.json'
            import ru from './translations/ru.json'
        """.trimIndent()
        assertEquals("./translations/en.json", I18nConfig.enImportPath(text))
    }

    @Test
    fun findsEnImportPathWithDoubleQuotes() {
        val text = """import en from "../locales/en.json""""
        assertEquals("../locales/en.json", I18nConfig.enImportPath(text))
    }

    @Test
    fun ignoresOtherDefaultImports() {
        val text = """
            import english from './translations/en.json'
            import en from './translations/en-US.json'
        """.trimIndent()
        // Binding must be exactly `en`.
        assertEquals("./translations/en-US.json", I18nConfig.enImportPath(text))
    }

    @Test
    fun returnsNullWhenNoEnImport() {
        val text = "import ru from './translations/ru.json'"
        assertNull(I18nConfig.enImportPath(text))
    }
}
