package com.webdx.analysis

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager

/**
 * Runs every inspection THIS plugin registers across the whole project, in one pass,
 * and shows the findings in the platform's standard Inspection Results tool window.
 *
 * The inspection set is selected by implementation-class package (`com.webdx.`), so it
 * automatically covers all per-language registrations of each inspection and any
 * inspection added later — without a hard-coded shortName list. The profile is built
 * in memory and never replaces or mutates the user's real profile.
 */
object WebdxInspectionRunner {

    const val PACKAGE_PREFIX = "com.webdx."

    private val log = logger<WebdxInspectionRunner>()

    /** Launch the project-wide run; results land in the standard Inspection Results window. */
    fun runAll(project: Project) {
        val profile = buildProfile(project)
        val managerEx = InspectionManager.getInstance(project) as InspectionManagerEx
        val context = managerEx.createNewGlobalContext()
        context.setExternalProfile(profile)
        log.info("[WEBDX-ANALYSIS] running ${profile.allTools.count { it.isEnabled }} inspection(s) over the project")
        context.doInspections(AnalysisScope(project))
    }

    /** In-memory profile with ONLY this plugin's inspections enabled. Visible for testing. */
    fun buildProfile(project: Project): InspectionProfileImpl {
        val profileManager = ProjectInspectionProfileManager.getInstance(project)
        val profile = InspectionProfileImpl(
            "WebDX project analysis",
            InspectionToolRegistrar.getInstance(),
            profileManager,
        )
        profile.initInspectionTools(project)
        profile.disableAllTools(project)
        for (state in profile.allTools) {
            val wrapper = state.tool
            if (wrapper.tool::class.java.name.startsWith(PACKAGE_PREFIX)) {
                profile.setToolEnabled(wrapper.shortName, true, project)
            }
        }
        return profile
    }
}
