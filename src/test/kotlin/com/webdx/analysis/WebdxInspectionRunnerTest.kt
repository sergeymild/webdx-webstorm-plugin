package com.webdx.analysis

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class WebdxInspectionRunnerTest : BasePlatformTestCase() {

    override fun setUp() {
        // InspectionProfileImpl.initInspectionTools() is a no-op in unit-test mode unless
        // INIT_INSPECTIONS is true. Flip it on for the duration of this test class.
        InspectionProfileImpl.INIT_INSPECTIONS = true
        super.setUp()
    }

    override fun tearDown() {
        try {
            InspectionProfileImpl.INIT_INSPECTIONS = false
        } finally {
            super.tearDown()
        }
    }

    fun testProfileEnablesOnlyWebdxInspections() {
        val profile = WebdxInspectionRunner.buildProfile(project)
        val enabled = profile.allTools.filter { it.isEnabled }

        // Nothing outside our plugin is enabled.
        val foreign = enabled.filterNot {
            it.tool.tool::class.java.name.startsWith(WebdxInspectionRunner.PACKAGE_PREFIX)
        }
        assertTrue(
            "non-webdx tools enabled: ${foreign.map { it.tool.shortName }}",
            foreign.isEmpty(),
        )
    }

    fun testProfileEnablesKnownUnusedInspections() {
        val profile = WebdxInspectionRunner.buildProfile(project)
        val enabledShortNames = profile.allTools.filter { it.isEnabled }.map { it.tool.shortName }.toSet()

        // A representative tool from each unused/dead family must be enabled.
        for (name in listOf("CssModuleUnusedClass", "ScssUnusedSymbol", "DeadExport", "DeadReExport")) {
            assertTrue("expected '$name' enabled, got $enabledShortNames", enabledShortNames.contains(name))
        }
    }

    fun testEnabledToolWrapsRealInspection() {
        // Smoke (tool-supplier level, per spec): the enabled unused-class tool in our
        // profile really wraps the plugin's inspection implementation — i.e. clicking the
        // button would run the actual analysis, not an empty profile.
        val profile = WebdxInspectionRunner.buildProfile(project)
        val state = profile.allTools.single { it.isEnabled && it.tool.shortName == "CssModuleUnusedClass" }
        assertEquals(
            "com.webdx.cssmodules.CssModuleUnusedClassInspection",
            state.tool.tool::class.java.name,
        )
    }

    fun testSelectorEnablesOnlyMatchingInspection() {
        val target = "com.webdx.cssmodules.CssModuleUnusedClassInspection"
        val profile = WebdxInspectionRunner.buildProfile(project) { it == target }
        val enabled = profile.allTools.filter { it.isEnabled }

        assertTrue("expected the selected inspection's registrations to be enabled", enabled.isNotEmpty())
        assertTrue(
            "selector leaked other inspections: ${enabled.map { it.tool.tool::class.java.name }.toSet()}",
            enabled.all { it.tool.tool::class.java.name == target },
        )
        // A sibling inspection in the same package must stay disabled.
        val override = profile.allTools.single { it.tool.shortName == "CssModuleOverrideClass" }
        assertFalse("non-selected inspection must be disabled", override.isEnabled)
    }

    fun testEachWebdxInspectionIsRegisteredOnce() {
        // Each inspection implementation must be registered exactly once. Registering the same
        // class under several overlapping languages (e.g. JavaScript + TypeScript + TypeScript JSX,
        // which are all dialects) makes a single file match multiple tools and shows the same
        // warning several times. localInspection applies to dialects by default, so one base-language
        // registration (JavaScript for JS/TS, CSS for SCSS/SASS/LESS) covers every file once.
        val profile = WebdxInspectionRunner.buildProfile(project)
        val byClass = profile.allTools
            .filter { it.tool.tool::class.java.name.startsWith(WebdxInspectionRunner.PACKAGE_PREFIX) }
            .groupBy { it.tool.tool::class.java.name }
        val duplicated = byClass
            .filter { (_, tools) -> tools.size > 1 }
            .mapValues { (_, tools) -> tools.map { it.tool.shortName }.sorted() }
        assertTrue(
            "inspections registered more than once (duplicate warnings per file): $duplicated",
            duplicated.isEmpty(),
        )
    }

    fun testEveryCatalogedAnalysisMatchesARegisteredInspection() {
        // Guards the ANALYSES catalog against implementation-class FQN typos: every label must map
        // to an inspection the platform actually registers, otherwise its button would silently
        // run nothing.
        val profile = WebdxInspectionRunner.buildProfile(project)
        val enabledClasses = profile.allTools
            .filter { it.isEnabled }
            .map { it.tool.tool::class.java.name }
            .toSet()
        for (analysis in WebdxInspectionRunner.ANALYSES) {
            assertTrue(
                "catalog entry '${analysis.label}' -> ${analysis.inspectionClass} matches no registered inspection",
                enabledClasses.contains(analysis.inspectionClass),
            )
        }
    }

    fun testEveryWebdxInspectionHasDescription() {
        // The batch Inspection Results view calls InspectionToolWrapper.loadDescription() when
        // a result node is selected and throws "Inspection #X has no description" if it is
        // blank. On-the-fly per-file inspection never needs a description, so this only
        // surfaces through our project-wide run. Every WebDX inspection must supply one.
        val profile = WebdxInspectionRunner.buildProfile(project)
        val missing = profile.allTools
            .filter {
                it.isEnabled && it.tool.tool::class.java.name.startsWith(WebdxInspectionRunner.PACKAGE_PREFIX)
            }
            .filter { it.tool.loadDescription().isNullOrBlank() }
            .map { it.tool.shortName }
            .toSortedSet()
        assertTrue(
            "WebDX inspections with no description (crash the Inspection Results view): $missing",
            missing.isEmpty(),
        )
    }
}
