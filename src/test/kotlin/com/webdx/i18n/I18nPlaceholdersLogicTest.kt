package com.webdx.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure logic: extracting `{{placeholder}}` names from a translation value. */
class I18nPlaceholdersLogicTest {

    @Test
    fun extractsSingle() {
        assertEquals(setOf("step"), I18nKeys.placeholdersOf("Step {{step}}"))
    }

    @Test
    fun extractsMultiple() {
        assertEquals(setOf("price1", "price2"), I18nKeys.placeholdersOf("{{price1}} to {{price2}}"))
    }

    @Test
    fun trimsWhitespace() {
        assertEquals(setOf("count", "name"), I18nKeys.placeholdersOf("{{ count }} of {{ name }}"))
    }

    @Test
    fun takesNameBeforeFormatComma() {
        assertEquals(setOf("value"), I18nKeys.placeholdersOf("{{value, number}}"))
    }

    @Test
    fun noPlaceholdersGivesEmpty() {
        assertEquals(emptySet<String>(), I18nKeys.placeholdersOf("Just text, no vars"))
    }

    @Test
    fun ignoresEmptyBraces() {
        assertEquals(emptySet<String>(), I18nKeys.placeholdersOf("a {{}} b {{   }}"))
    }
}
