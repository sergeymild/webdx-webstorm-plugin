package com.webdx.barrels

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BarrelExportsPlanTest : BasePlatformTestCase() {

    private fun tsconfig(body: String) = myFixture.addFileToProject("tsconfig.json", body)
    private fun lines(p: BarrelExports.Plan?) =
        p?.edits?.map { it.indexFile.parent.name to it.line }

    fun testIntchNamedStarStyle() {
        tsconfig("""{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './Other'\n")
        myFixture.addFileToProject("components/Button/index.ts", "export * from './nothing-yet'\n")
        val file = myFixture.addFileToProject("components/Button/Button.tsx", "export const Button = 1\n")
        val plan = BarrelExports.planFor(file, "Button", false, project)
        assertEquals(
            listOf(
                "Button" to "export * from './Button'",
                "components" to "export * from './Button'",
            ),
            lines(plan),
        )
        assertEquals("components", plan!!.moduleRootLabel)
    }

    fun testTouchpointDefaultExportConversion() {
        tsconfig("""{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("src/containers/index.ts", "export * from './Basic'\n")
        myFixture.addFileToProject(
            "src/containers/Touchpoint/index.ts",
            "export { default } from './Touchpoint'\n",
        )
        val file = myFixture.addFileToProject(
            "src/containers/Touchpoint/Touchpoint.tsx",
            "export default function Touchpoint() {}\n",
        )
        val plan = BarrelExports.planFor(file, "Touchpoint", true, project)
        // Leaf already forwards default-as-default -> skipped; parent converts default->named.
        assertEquals(
            listOf("containers" to "export { default as Touchpoint } from './Touchpoint'"),
            lines(plan),
        )
    }

    fun testMuseNamedStyleMultiSegmentWithSemicolons() {
        tsconfig("""{ "compilerOptions": { "baseUrl": ".", "paths": { "@m/score": ["./src/screens/score/index.ts"] } } }""")
        myFixture.addFileToProject("package.json", """{ "name": "rn" }""")
        myFixture.addFileToProject("src/screens/score/index.ts", "export { A } from './A';\n")
        val file = myFixture.addFileToProject(
            "src/screens/score/components/modals/Sheet.tsx",
            "export const Sheet = 1\n",
        )
        val plan = BarrelExports.planFor(file, "Sheet", false, project)
        assertEquals(
            listOf("score" to "export { Sheet } from './components/modals/Sheet';"),
            lines(plan),
        )
    }

    fun testNullWhenAlreadyFullyWired() {
        tsconfig("""{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './Button'\n")
        myFixture.addFileToProject("components/Button/index.ts", "export * from './Button'\n")
        val file = myFixture.addFileToProject("components/Button/Button.tsx", "export const Button = 1\n")
        assertNull(BarrelExports.planFor(file, "Button", false, project))
    }

    fun testNullWhenNoBarrelAncestor() {
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        val file = myFixture.addFileToProject("loose/Button.tsx", "export const Button = 1\n")
        assertNull(BarrelExports.planFor(file, "Button", false, project))
    }
}
