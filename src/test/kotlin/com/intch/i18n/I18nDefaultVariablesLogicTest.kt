package com.intch.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure logic: extract auto-provided variable names from `interpolation.defaultVariables`. */
class I18nDefaultVariablesLogicTest {

    @Test
    fun extractsDefaultVariableKeys() {
        val text = """
            interpolation: {
              escapeValue: false,
              defaultVariables: {
                specialistsCount: SPECIALISTS_COUNT,
                specialistsCountFull: SPECIALISTS_COUNT_FULL,
              },
            },
        """.trimIndent()
        assertEquals(setOf("specialistsCount", "specialistsCountFull"), I18nConfig.defaultVariableNames(text))
    }

    @Test
    fun emptyWhenNoDefaultVariables() {
        assertEquals(emptySet<String>(), I18nConfig.defaultVariableNames("interpolation: { escapeValue: false }"))
    }
}
