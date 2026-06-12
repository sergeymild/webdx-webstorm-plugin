package com.webdx.cssmodules

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

    @Test
    fun `parses baseUrl and a wildcard path mapping`() {
        val ts = """
            { "compilerOptions": { "baseUrl": ".", "paths": { "@/*": ["./*"] } } }
        """.trimIndent()
        val cfg = CssModules.tsconfigAliases(ts)
        assertEquals(".", cfg.baseUrl)
        assertEquals(mapOf("@/*" to "./*"), cfg.paths)
    }

    @Test
    fun `parses paths when baseUrl is absent`() {
        val ts = """{ "compilerOptions": { "paths": { "@/*": ["./*"], "~/*": ["./src/*"] } } }"""
        val cfg = CssModules.tsconfigAliases(ts)
        assertEquals(null, cfg.baseUrl)
        assertEquals(mapOf("@/*" to "./*", "~/*" to "./src/*"), cfg.paths)
    }

    @Test
    fun `empty config when no paths`() {
        val cfg = CssModules.tsconfigAliases("""{ "compilerOptions": { "strict": true } }""")
        assertEquals(null, cfg.baseUrl)
        assertEquals(emptyMap<String, String>(), cfg.paths)
    }

    @Test
    fun `extracts mixin function variable and placeholder definitions`() {
        val scss = """
            @mixin remove-scrollbar { overflow: hidden; }
            @function rem(${'$'}px) { @return ${'$'}px; }
            ${'$'}brand-color: #fff;
            %card-base { padding: 4px; }
            .local { color: red; }
        """.trimIndent()
        assertEquals(
            setOf("remove-scrollbar", "rem", "brand-color", "card-base"),
            CssModules.scssDefinedSymbols(scss),
        )
    }

    @Test
    fun `no symbols in plain rules`() {
        assertEquals(emptyList<String>(), CssModules.scssDefinedSymbols(".a { color: red; }\n.b { }").toList())
    }
}
