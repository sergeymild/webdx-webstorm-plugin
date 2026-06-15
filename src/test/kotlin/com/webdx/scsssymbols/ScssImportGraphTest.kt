package com.webdx.scsssymbols

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssImportGraphTest : BasePlatformTestCase() {

    fun testProvidedFollowsForwardAndImportTransitively() {
        val c = myFixture.addFileToProject("c.scss", "\$c: 1;")
        val b = myFixture.addFileToProject("b.scss", "@forward './c.scss';\n\$b: 1;")
        val a = myFixture.addFileToProject("a.scss", "@forward './b.scss';\n\$a: 1;")
        val provided = ScssImportGraph.provided(project, a.virtualFile)
        assertEquals(
            setOf("a.scss", "b.scss", "c.scss"),
            provided.map { it.name }.toSet(),
        )
        // unrelated file not pulled in
        assertFalse(provided.contains(c.virtualFile.parent.findChild("nope.scss")))
    }

    fun testProvidedIsCycleSafe() {
        myFixture.addFileToProject("x.scss", "@forward './y.scss';")
        val y = myFixture.addFileToProject("y.scss", "@forward './x.scss';\n\$y: 1;")
        val provided = ScssImportGraph.provided(project, y.virtualFile)
        assertEquals(setOf("x.scss", "y.scss"), provided.map { it.name }.toSet())
    }

    fun testGlobalScopeFromImportAndUseStar() {
        val vars = myFixture.addFileToProject("vars.scss", "\$x: 1;")
        val pal = myFixture.addFileToProject("pal.scss", "\$p: 1;")
        val f = myFixture.addFileToProject(
            "f.scss",
            "@import './vars.scss';\n@use './pal.scss' as *;\n.a { width: \$x; }",
        )
        val scope = ScssImportGraph.globalScopeFiles(project, f.virtualFile).map { it.name }.toSet()
        assertEquals(setOf("f.scss", "vars.scss", "pal.scss"), scope)
    }

    fun testNamespacedUseIsolatedFromGlobal() {
        val vars = myFixture.addFileToProject("vars.scss", "\$x: 1;")
        val f = myFixture.addFileToProject("f.scss", "@use './vars.scss' as v;\n.a { width: v.\$x; }")
        // not in the bare/global scope …
        assertEquals(setOf("f.scss"), ScssImportGraph.globalScopeFiles(project, f.virtualFile).map { it.name }.toSet())
        // … but reachable under namespace `v`
        assertEquals(setOf("vars.scss"), ScssImportGraph.namespaceTargets(project, f.virtualFile)["v"]!!.map { it.name }.toSet())
    }

    fun testDefaultNamespaceIsBasename() {
        myFixture.addFileToProject("vars.scss", "\$x: 1;")
        val f = myFixture.addFileToProject("f.scss", "@use './vars.scss';\n.a { width: vars.\$x; }")
        assertEquals(setOf("vars.scss"), ScssImportGraph.namespaceTargets(project, f.virtualFile)["vars"]!!.map { it.name }.toSet())
    }
}
