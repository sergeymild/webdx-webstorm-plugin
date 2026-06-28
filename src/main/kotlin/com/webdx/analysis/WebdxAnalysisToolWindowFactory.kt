package com.webdx.analysis

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.Scrollable

/**
 * Left-stripe WebDX tool window. Its panel holds one button per analysis plus a "Run all"
 * button; each runs the matching inspection(s) project-wide via [WebdxAnalysisRunController],
 * with findings shown in the platform's standard Inspection Results window. While a run is in
 * progress every run button is disabled and a Stop button cancels it.
 */
class WebdxAnalysisToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = buildPanel(project, WebdxAnalysisRunController(project))
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Builds the tool-window panel and wires it to [controller]. The controller drives button
     * enablement through [WebdxAnalysisRunController.onStateChange] so the UI never diverges from
     * the real run state. Visible for testing.
     */
    fun buildPanel(
        project: Project,
        controller: WebdxAnalysisRunController = WebdxAnalysisRunController(project),
    ): JComponent {
        val runButtons = mutableListOf<JButton>()
        lateinit var stopButton: JButton
        lateinit var statusLabel: JBLabel

        val panel = panel {
            row {
                text(
                    "Scan the whole project for unused code, dead exports, " +
                        "and unknown CSS classes / translation keys.",
                )
            }
            row {
                val cell = button("Run all analysis") { controller.run("All analysis") }
                    .align(AlignX.FILL)
                    .applyToComponent { name = "webdx.run.all" }
                runButtons += cell.component
            }
            separator()
            for (analysis in WebdxInspectionRunner.ANALYSES) {
                row {
                    val cell = button(analysis.label) {
                        controller.run(analysis.label) { it == analysis.inspectionClass }
                    }.align(AlignX.FILL).applyToComponent {
                        name = "webdx.run.${analysis.inspectionClass}"
                    }
                    runButtons += cell.component
                }
            }
            separator()
            row {
                val cell = button("Stop") { controller.stop() }
                    .align(AlignX.FILL)
                    .applyToComponent { name = "webdx.stop" }
                stopButton = cell.component
            }
            row {
                statusLabel = JBLabel().also { it.name = "webdx.status" }
                cell(statusLabel).align(AlignX.FILL)
            }
        }.apply { border = JBUI.Borders.empty(10) }

        fun refresh() {
            val running = controller.isRunning
            runButtons.forEach { it.isEnabled = !running }
            stopButton.isEnabled = running
            statusLabel.text = controller.runningTitle?.let { "Running: $it…" } ?: "Idle"
        }
        controller.onStateChange = ::refresh
        refresh()

        // Track the viewport width (no horizontal scroll): the column of buttons fills the
        // tool-window width and shrinks with it instead of forcing a horizontal scrollbar.
        val scrollPane = JBScrollPane(
            WidthTrackingWrapper(panel),
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
        )
        scrollPane.border = JBUI.Borders.empty()
        return scrollPane
    }

    /**
     * Hosts [content] at the top and reports that it tracks the scroll viewport's width, so the
     * content is laid out at the viewport width (buttons resize with the window) rather than at its
     * own preferred width (which would trigger a horizontal scrollbar).
     */
    private class WidthTrackingWrapper(content: JComponent) :
        JBPanel<WidthTrackingWrapper>(BorderLayout()), Scrollable {

        init {
            isOpaque = false
            add(content, BorderLayout.NORTH)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 16
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 100
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
}
