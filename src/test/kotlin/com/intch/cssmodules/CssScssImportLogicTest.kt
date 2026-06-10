package com.intch.cssmodules

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for parsing `@import`/`@use`/`@forward` paths out of SCSS text. */
class CssScssImportLogicTest {

    @Test
    fun `extracts a single import path`() {
        assertEquals(
            listOf("@/src/containers/Onboarding/common.module.scss"),
            CssModules.scssImportPaths("""@import "@/src/containers/Onboarding/common.module.scss";"""),
        )
    }

    @Test
    fun `handles both quote styles`() {
        assertEquals(
            listOf("../a.module.scss", "./b.module.scss"),
            CssModules.scssImportPaths("@import '../a.module.scss';\n@import \"./b.module.scss\";"),
        )
    }

    @Test
    fun `handles a comma-separated import list`() {
        assertEquals(
            listOf("a.module.scss", "b.module.scss"),
            CssModules.scssImportPaths("""@import "a.module.scss", "b.module.scss";"""),
        )
    }

    @Test
    fun `handles use and forward with options`() {
        assertEquals(
            listOf("./theme.module.scss", "./vars"),
            CssModules.scssImportPaths("@use './theme.module.scss' as t;\n@forward './vars' with (\$x: 1);"),
        )
    }

    @Test
    fun `ignores non-import at-rules and plain text`() {
        assertEquals(
            emptyList<String>(),
            CssModules.scssImportPaths(".a { color: red; }\n@media screen { .b {} }"),
        )
    }
}
