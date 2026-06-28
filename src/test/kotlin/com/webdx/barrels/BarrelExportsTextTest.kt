package com.webdx.barrels

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.webdx.barrels.BarrelExports.Style

class BarrelExportsTextTest : BasePlatformTestCase() {

    fun testDetectStyleStarNoSemi() {
        val s = BarrelExports.detectStyle("export * from './A'\nexport * from './B'\n")
        assertEquals('\'', s.quote)
        assertFalse(s.semi)
        assertTrue(s.prefersStar)
    }

    fun testDetectStyleNamedWithSemiDoubleQuote() {
        val s = BarrelExports.detectStyle("export { A } from \"./A\";\nexport { B } from \"./B\";\n")
        assertEquals('"', s.quote)
        assertTrue(s.semi)
        assertFalse(s.prefersStar)
    }

    fun testReExportLineNamedStar() {
        val s = Style(quote = '\'', semi = false, prefersStar = true)
        assertEquals("export * from './Button'", BarrelExports.reExportLine("Button", false, "./Button", s))
    }

    fun testReExportLineNamedExplicit() {
        val s = Style(quote = '\'', semi = true, prefersStar = false)
        assertEquals("export { Button } from './Button';", BarrelExports.reExportLine("Button", false, "./Button", s))
    }

    fun testReExportLineDefaultAsAlwaysNamedFormEvenWhenStarPreferred() {
        val s = Style(quote = '\'', semi = false, prefersStar = true)
        assertEquals(
            "export { default as Touchpoint } from './Touchpoint'",
            BarrelExports.reExportLine("Touchpoint", true, "./Touchpoint", s),
        )
    }

    fun testForwardsNameNamed() {
        assertTrue(BarrelExports.forwardsName("export { Button } from './Button'", "Button", "./Button"))
        assertTrue(BarrelExports.forwardsName("export { Inner as Button } from './x'", "Button", "./x"))
        assertFalse(BarrelExports.forwardsName("export { Other } from './x'", "Button", "./x"))
    }

    fun testForwardsNameViaStarFromSameSpecifier() {
        assertTrue(BarrelExports.forwardsName("export * from './Button'", "Button", "./Button"))
        assertFalse(BarrelExports.forwardsName("export * from './Other'", "Button", "./Button"))
    }

    fun testForwardsDefaultFrom() {
        assertTrue(BarrelExports.forwardsDefaultFrom("export { default } from './Touchpoint'", "./Touchpoint"))
        assertFalse(BarrelExports.forwardsDefaultFrom("export { default } from './Other'", "./Touchpoint"))
    }
}
