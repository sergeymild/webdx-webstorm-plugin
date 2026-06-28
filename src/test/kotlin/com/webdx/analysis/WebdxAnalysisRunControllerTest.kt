package com.webdx.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class WebdxAnalysisRunControllerTest : BasePlatformTestCase() {

    fun testIdleStateByDefault() {
        val controller = WebdxAnalysisRunController(project)
        assertFalse("a fresh controller must not be running", controller.isRunning)
        assertNull("idle controller has no running title", controller.runningTitle)
    }

    fun testStopWhileIdleIsSafe() {
        val controller = WebdxAnalysisRunController(project)
        // No active run: stop must be a harmless no-op, not throw.
        controller.stop()
        assertFalse(controller.isRunning)
    }
}
