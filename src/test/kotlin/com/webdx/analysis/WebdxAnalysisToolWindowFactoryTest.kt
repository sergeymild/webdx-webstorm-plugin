package com.webdx.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Container
import javax.swing.JButton

class WebdxAnalysisToolWindowFactoryTest : BasePlatformTestCase() {

    fun testPanelHasRunAllButton() {
        val panel = WebdxAnalysisToolWindowFactory().buildPanel(project)
        val button = findButton(panel, "webdx.run.all")
        assertNotNull("panel must contain the run-all button", button)
        assertEquals("Run all analysis", button!!.text)
    }

    fun testPanelHasOnePerAnalysisButton() {
        val panel = WebdxAnalysisToolWindowFactory().buildPanel(project)
        for (analysis in WebdxInspectionRunner.ANALYSES) {
            val button = findButton(panel, "webdx.run.${analysis.inspectionClass}")
            assertNotNull("missing run button for ${analysis.label}", button)
            assertEquals(analysis.label, button!!.text)
        }
    }

    fun testStopButtonDisabledAndRunButtonsEnabledWhenIdle() {
        val controller = WebdxAnalysisRunController(project)
        val panel = WebdxAnalysisToolWindowFactory().buildPanel(project, controller)

        val stop = findButton(panel, "webdx.stop")
        assertNotNull("panel must contain the stop button", stop)
        assertFalse("stop must be disabled while idle", stop!!.isEnabled)

        val runAll = findButton(panel, "webdx.run.all")!!
        assertTrue("run buttons must be enabled while idle", runAll.isEnabled)
    }

    private fun findButton(c: Container, name: String): JButton? {
        for (child in c.components) {
            if (child is JButton && child.name == name) return child
            if (child is Container) findButton(child, name)?.let { return it }
        }
        return null
    }
}
