package com.webdx.cssmodules

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for the import binding name chosen when auto-importing a
 * sibling CSS module: keep the typed name when the module matches the current
 * component, otherwise derive a camelCase name from the module file name.
 */
class CssModuleImportBindingTest {

    // --- module matches the current component -> keep what the user typed ---

    @Test
    fun `keeps typed name when module matches component`() {
        assertEquals("styles", importBindingFor("styles", "Profile.module.scss", "Profile.tsx"))
    }

    @Test
    fun `match is independent of extension`() {
        assertEquals("styles", importBindingFor("styles", "Profile.module.css", "Profile.tsx"))
        assertEquals("styles", importBindingFor("styles", "Profile.module.less", "Profile.tsx"))
    }

    @Test
    fun `match is case-insensitive`() {
        assertEquals("styles", importBindingFor("styles", "profile.module.scss", "Profile.tsx"))
    }

    @Test
    fun `preserves an arbitrary typed name on a matching module`() {
        assertEquals("css", importBindingFor("css", "Profile.module.scss", "Profile.tsx"))
    }

    // --- module differs -> derive camelCase name from the module ------------

    @Test
    fun `derives camelCase name when module differs`() {
        assertEquals("sidebar", importBindingFor("styles", "Sidebar.module.scss", "Profile.tsx"))
    }

    @Test
    fun `lowercases only the first char of a PascalCase module`() {
        assertEquals("userProfile", importBindingFor("styles", "UserProfile.module.scss", "Profile.tsx"))
    }

    @Test
    fun `joins kebab-case module into camelCase`() {
        assertEquals("userProfile", importBindingFor("styles", "user-profile.module.scss", "Index.tsx"))
    }

    @Test
    fun `joins snake_case module into camelCase`() {
        assertEquals("userProfile", importBindingFor("styles", "user_profile.module.scss", "Index.tsx"))
    }

    // --- fallback when no valid identifier can be formed --------------------

    @Test
    fun `falls back to typed name when module starts with a digit`() {
        assertEquals("styles", importBindingFor("styles", "2cols.module.scss", "Index.tsx"))
    }
}
