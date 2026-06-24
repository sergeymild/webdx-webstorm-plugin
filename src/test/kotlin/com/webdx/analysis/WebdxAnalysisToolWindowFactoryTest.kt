package com.webdx.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Container
import javax.swing.JButton

class WebdxAnalysisToolWindowFactoryTest : BasePlatformTestCase() {

    fun testPanelHasRunButton() {
        val panel = WebdxAnalysisToolWindowFactory().buildPanel(project)
        val button = findButton(panel)
        assertNotNull("panel must contain the run button", button)
        assertEquals("webdx.runAnalysis", button!!.name)
        assertEquals("Run Project Analysis", button.text)
    }

    private fun findButton(c: Container): JButton? {
        for (child in c.components) {
            if (child is JButton && child.name == "webdx.runAnalysis") return child
            if (child is Container) findButton(child)?.let { return it }
        }
        return null
    }
}
