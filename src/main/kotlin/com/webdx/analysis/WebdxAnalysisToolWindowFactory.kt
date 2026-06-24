package com.webdx.analysis

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

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

    /** The tool-window panel. Visible for testing. */
    fun buildPanel(project: Project): JComponent {
        val description = JBLabel(
            "<html>Проверить весь проект на неиспользуемый код, " +
                "мёртвые экспорты, неизвестные классы/ключи и т.д.</html>",
        )

        val runButton = JButton("Запустить проверку проекта").apply {
            name = "webdx.runAnalysis"
            addActionListener { onRun(project) }
        }
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(runButton) }

        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)
            add(description)
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(buttonRow)
        }

        // Top-align the column so the button doesn't get centered in a tall tool window.
        return JPanel(BorderLayout()).apply { add(column, BorderLayout.NORTH) }
    }

    private fun onRun(project: Project) {
        if (DumbService.getInstance(project).isDumb) {
            Messages.showInfoMessage(
                project,
                "Дождитесь окончания индексации проекта и попробуйте снова.",
                "WebDX: индексация",
            )
            return
        }
        WebdxInspectionRunner.runAll(project)
    }
}
