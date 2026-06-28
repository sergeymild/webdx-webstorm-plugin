package com.webdx.barrels

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BarrelExportsSymbolTest : BasePlatformTestCase() {

    private fun nameAt(filename: String, text: String): Pair<String, Boolean>? {
        myFixture.configureByText(filename, text)
        val el = myFixture.file.findElementAt(myFixture.caretOffset)!!
        return BarrelExports.exportedNameAt(el)
    }

    fun testNamedConst() {
        assertEquals("Button" to false, nameAt("Button.tsx", "export const Butt<caret>on = () => null\n"))
    }

    fun testNamedFunction() {
        assertEquals("Foo" to false, nameAt("Foo.ts", "export function F<caret>oo() {}\n"))
    }

    fun testDefaultFunction() {
        assertEquals("Avatar" to true, nameAt("Avatar.tsx", "export default function Ava<caret>tar() {}\n"))
    }

    fun testLocalExportSpecifier() {
        assertEquals("Bar" to false, nameAt("x.ts", "const Bar = 1\nexport { B<caret>ar }\n"))
    }

    fun testNonExportedReturnsNull() {
        assertNull(nameAt("x.ts", "const Pri<caret>vate = 1\n"))
    }
}
