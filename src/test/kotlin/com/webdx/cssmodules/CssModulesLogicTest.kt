package com.webdx.cssmodules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the filename predicates in [CssModules] — no IDE platform,
 * no PSI, no project. Fast and deterministic.
 */
class CssModulesLogicTest {

    @Test
    fun `recognises every css-module extension`() {
        assertTrue(CssModules.isModuleFileName("Foo.module.css"))
        assertTrue(CssModules.isModuleFileName("Foo.module.scss"))
        assertTrue(CssModules.isModuleFileName("Foo.module.sass"))
        assertTrue(CssModules.isModuleFileName("Foo.module.less"))
    }

    @Test
    fun `module match is case-insensitive`() {
        assertTrue(CssModules.isModuleFileName("FOO.MODULE.SCSS"))
        assertTrue(CssModules.isModuleFileName("Foo.Module.Css"))
    }

    @Test
    fun `plain stylesheets are not css modules`() {
        assertFalse(CssModules.isModuleFileName("styles.css"))
        assertFalse(CssModules.isModuleFileName("styles.scss"))
        assertFalse(CssModules.isModuleFileName("global.less"))
    }

    @Test
    fun `module substring in the middle does not count`() {
        // ".module." must be the tail before the extension, not anywhere.
        assertFalse(CssModules.isModuleFileName("module.css.bak"))
        assertFalse(CssModules.isModuleFileName("Foo.module.scss.map"))
        assertFalse(CssModules.isModuleFileName("Foo.modulexcss"))
    }

    @Test
    fun `non stylesheet files are not css modules`() {
        assertFalse(CssModules.isModuleFileName("Foo.module.ts"))
        assertFalse(CssModules.isModuleFileName("README.md"))
        assertFalse(CssModules.isModuleFileName(""))
    }

    @Test
    fun `recognises every js-like extension`() {
        for (ext in listOf("tsx", "ts", "jsx", "js", "mts", "cts", "mjs", "cjs")) {
            assertTrue("expected .$ext to be js-like", CssModules.isJsLikeFileName("File.$ext"))
        }
    }

    @Test
    fun `js-like match is case-insensitive`() {
        assertTrue(CssModules.isJsLikeFileName("Component.TSX"))
        assertTrue(CssModules.isJsLikeFileName("Component.Js"))
    }

    @Test
    fun `non js files are not js-like`() {
        assertFalse(CssModules.isJsLikeFileName("Foo.module.scss"))
        assertFalse(CssModules.isJsLikeFileName("styles.css"))
        assertFalse(CssModules.isJsLikeFileName("notes.txt"))
        assertFalse(CssModules.isJsLikeFileName("archive.json"))
        assertFalse(CssModules.isJsLikeFileName(""))
    }
}
