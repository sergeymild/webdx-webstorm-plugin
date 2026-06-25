package com.webdx.analysis

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager

/** One runnable analysis: a user-facing label and the inspection it enables. */
data class WebdxAnalysis(val label: String, val inspectionClass: String)

/**
 * Builds in-memory inspection profiles scoped to THIS plugin's inspections.
 *
 * Inspections are selected by implementation-class FQN, so each entry automatically
 * covers all per-language registrations of that inspection. A run can enable every
 * WebDX inspection (the "Run all" button) or just one (a per-category button). The
 * profile is built in memory and never replaces or mutates the user's real profile.
 */
object WebdxInspectionRunner {

    const val PACKAGE_PREFIX = "com.webdx."

    /**
     * The individually runnable analyses, in display order. Each maps to one inspection
     * implementation class; "Run all" enables every WebDX inspection regardless of this list.
     */
    val ANALYSES: List<WebdxAnalysis> = listOf(
        WebdxAnalysis("Unused CSS classes", "com.webdx.cssmodules.CssModuleUnusedClassInspection"),
        WebdxAnalysis("Unknown CSS classes", "com.webdx.cssmodules.CssModuleUnknownClassInspection"),
        WebdxAnalysis("CSS class overrides", "com.webdx.cssmodules.CssModuleOverrideClassInspection"),
        WebdxAnalysis("Unused SCSS symbols", "com.webdx.scsssymbols.ScssUnusedSymbolInspection"),
        WebdxAnalysis("Unknown RN style keys", "com.webdx.rnstyles.RnStyleUnknownKeyInspection"),
        WebdxAnalysis("Unused RN style keys", "com.webdx.rnstyles.RnStyleUnusedKeyInspection"),
        WebdxAnalysis("Dead exports", "com.webdx.deadexports.DeadExportInspection"),
        WebdxAnalysis("Dead re-exports", "com.webdx.deadexports.DeadReExportInspection"),
        WebdxAnalysis("Unknown i18n keys", "com.webdx.i18n.I18nUnknownKeyInspection"),
        WebdxAnalysis("i18n interpolation issues", "com.webdx.i18n.I18nInterpolationInspection"),
    )

    /**
     * In-memory profile enabling the WebDX inspections whose implementation-class FQN matches
     * [selector]. The default selector enables every WebDX inspection ("Run all"). Visible for
     * testing.
     */
    fun buildProfile(
        project: Project,
        selector: (String) -> Boolean = { true },
    ): InspectionProfileImpl {
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
            val className = wrapper.tool::class.java.name
            if (className.startsWith(PACKAGE_PREFIX) && selector(className)) {
                profile.setToolEnabled(wrapper.shortName, true, project)
            }
        }
        return profile
    }
}
