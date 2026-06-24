package com.webdx.analysis

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * Left-stripe WebDX tool window. Its panel holds one button that runs every WebDX
 * inspection across the project ([WebdxInspectionRunner]); findings appear in the
 * platform's standard Inspection Results window. Intentionally minimal — room left
 * for future filters/history.
 */
class WebdxAnalysisToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(buildPanel(project), "", false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * The tool-window panel. Built with the platform UI DSL so the description and button
     * stay top-left aligned and the text wraps to the tool-window width. Visible for testing.
     */
    fun buildPanel(project: Project): JComponent =
        panel {
            row {
                text(
                    "Scan the whole project for unused code, dead exports, " +
                        "and unknown CSS classes / translation keys.",
                )
            }
            row {
                button("Run Project Analysis") { onRun(project) }
                    .applyToComponent { name = "webdx.runAnalysis" }
            }
        }.apply { border = JBUI.Borders.empty(10) }

    private fun onRun(project: Project) {
        if (DumbService.getInstance(project).isDumb) {
            Messages.showInfoMessage(
                project,
                "Wait for project indexing to finish and try again.",
                "WebDX",
            )
            return
        }
        WebdxInspectionRunner.runAll(project)
    }
}
