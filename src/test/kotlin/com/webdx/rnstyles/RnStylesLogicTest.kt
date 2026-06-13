package com.webdx.rnstyles

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure (no-PSI) tests for the import/destructuring brace parsers. */
class RnStylesLogicTest {

    @Test fun parsesPlainNamedImports() {
        assertEquals(mapOf("styles" to "styles"), RnStyles.parseNamedImports(" styles "))
        assertEquals(mapOf("a" to "a", "styles" to "styles"), RnStyles.parseNamedImports("a, styles"))
    }

    @Test fun parsesAliasedNamedImports() {
        assertEquals(mapOf("s" to "styles"), RnStyles.parseNamedImports("styles as s"))
        assertEquals(mapOf("a" to "a", "s" to "styles"), RnStyles.parseNamedImports("a, styles as s"))
    }

    @Test fun parsesShorthandDestructuring() {
        assertEquals(mapOf("title" to "title", "text" to "text"), RnStyles.parseDestructuredEntries("title, text"))
    }

    @Test fun parsesRenamedAndDefaultedDestructuring() {
        // `{ title: t }` -> local `t` from key `title`; `{ x = 5 }` -> key `x`; `...rest` ignored.
        assertEquals(
            mapOf("t" to "title", "x" to "x"),
            RnStyles.parseDestructuredEntries("title: t, x = 5, ...rest"),
        )
    }

    @Test fun emptyInputReturnsEmptyMap() {
        assertEquals(emptyMap<String, String>(), RnStyles.parseNamedImports(""))
        assertEquals(emptyMap<String, String>(), RnStyles.parseDestructuredEntries(""))
    }
}
