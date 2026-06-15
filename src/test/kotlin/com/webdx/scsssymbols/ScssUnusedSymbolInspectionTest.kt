package com.webdx.scsssymbols

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssUnusedSymbolInspectionTest : BasePlatformTestCase() {

    fun testUnusedSymbolsGreyedUsedNot() {
        val vars = myFixture.addFileToProject(
            "vars.scss",
            "\$used: 1;\n\$dead: 2;\n@function deadFn(\$a) { @return \$a; }\n@mixin deadMix { x: 1; }\n%deadPh { y: 1; }",
        )
        myFixture.addFileToProject("f.scss", "@import './vars.scss';\n.a { width: \$used; }")
        myFixture.configureFromExistingVirtualFile(vars.virtualFile)
        myFixture.enableInspections(ScssUnusedSymbolInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue("dead var greyed, got: $descriptions", descriptions.any { it.contains("'dead'") && it.contains("Unused") })
        assertTrue("dead fn greyed, got: $descriptions", descriptions.any { it.contains("'deadFn'") })
        assertTrue("dead mixin greyed, got: $descriptions", descriptions.any { it.contains("'deadMix'") })
        assertTrue("dead placeholder greyed, got: $descriptions", descriptions.any { it.contains("'deadPh'") })
        assertFalse("used var not greyed, got: $descriptions", descriptions.any { it.contains("'used'") })
    }

    fun testNamespacedAndIncludeCountAsUsed() {
        val vars = myFixture.addFileToProject("vars.scss", "\$nv: 1;\n@mixin m { x: 1; }")
        myFixture.addFileToProject("f.scss", "@use './vars.scss' as v;\n.a { width: v.\$nv; @include v.m; }")
        myFixture.configureFromExistingVirtualFile(vars.virtualFile)
        myFixture.enableInspections(ScssUnusedSymbolInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse("namespaced-used not greyed, got: $descriptions", descriptions.any { it.contains("'nv'") || it.contains("'m'") })
    }
}
