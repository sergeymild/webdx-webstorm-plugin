# WebDX Unused-Code Analysis Tool Window — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a WebDX button in the left tool-window stripe that runs all of the plugin's own inspections across the whole project and shows the findings in WebStorm's standard Inspection Results window.

**Architecture:** A UI-free `WebdxInspectionRunner` builds a throwaway in-memory `InspectionProfileImpl` with only `com.webdx.*` inspections enabled (selected by implementation-class package, not a hard-coded list), then runs `GlobalInspectionContextImpl.doInspections(AnalysisScope(project))`. A `ToolWindowFactory` provides a minimal panel with one button that calls the runner. Registered via a `<toolWindow>` EP with a 13×13 SVG stripe icon.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (WebStorm 2025.2+ / build 261 target), JUnit via `BasePlatformTestCase`, Gradle IntelliJ Platform plugin.

## Global Constraints

- Plugin id stays `com.webdx.css-modules-scoped-usages`; display name **WebDX**.
- Target platform: **WebStorm 2026.1 (build 261)**; verified to compile against the 2025.2 SDK in the Gradle cache.
- All new sources under `src/main/kotlin/com/webdx/analysis/`.
- Inspection selection is by implementation-class package prefix `com.webdx.` — never a hard-coded shortName list.
- The user's real inspection profile must never be mutated or persisted; the profile we build is in-memory and thrown away.
- Commit messages: do NOT add a Claude/Anthropic co-author trailer (a global git hook strips it).
- Run tests with `./gradlew test`; compile with `./gradlew compileKotlin`.

---

### Task 1: `WebdxInspectionRunner` — profile builder + project run

**Files:**
- Create: `src/main/kotlin/com/webdx/analysis/WebdxInspectionRunner.kt`
- Test: `src/test/kotlin/com/webdx/analysis/WebdxInspectionRunnerTest.kt`

**Interfaces:**
- Consumes: IntelliJ Platform —
  `com.intellij.profile.codeInspection.ProjectInspectionProfileManager.getInstance(project)` (a `BaseInspectionProfileManager`),
  `com.intellij.codeInspection.ex.InspectionToolRegistrar.getInstance()` (an `InspectionToolsSupplier`),
  `com.intellij.codeInspection.ex.InspectionProfileImpl(String, InspectionToolsSupplier, BaseInspectionProfileManager)`,
  `InspectionProfileImpl.initInspectionTools(project)`, `.disableAllTools(project)`, `.setToolEnabled(shortName, true, project)`, `.getAllTools(): List<ScopeToolState>`,
  `ScopeToolState.getTool(): InspectionToolWrapper<*,*>`, `.isEnabled`, `InspectionToolWrapper.getTool()`, `.getShortName()`,
  `(InspectionManager.getInstance(project) as InspectionManagerEx).createNewGlobalContext()`,
  `GlobalInspectionContextImpl.setExternalProfile(profile)` / `.doInspections(AnalysisScope(project))`.
- Produces:
  - `object WebdxInspectionRunner`
  - `fun buildProfile(project: Project): InspectionProfileImpl` — in-memory profile, only our tools enabled (visible for testing)
  - `fun runAll(project: Project)` — builds the profile and launches the project-wide global inspection
  - `const val PACKAGE_PREFIX = "com.webdx."`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/webdx/analysis/WebdxInspectionRunnerTest.kt`:

```kotlin
package com.webdx.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class WebdxInspectionRunnerTest : BasePlatformTestCase() {

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
        for (name in listOf("CssModuleUnusedClass", "ScssUnusedSymbol", "DeadExportTsx", "DeadReExportTsx")) {
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.webdx.analysis.WebdxInspectionRunnerTest"`
Expected: FAIL — `WebdxInspectionRunner` is unresolved (compilation error).

- [ ] **Step 3: Write minimal implementation**

`src/main/kotlin/com/webdx/analysis/WebdxInspectionRunner.kt`:

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.webdx.analysis.WebdxInspectionRunnerTest"`
Expected: PASS (all three tests).

If `testProfileEnablesKnownUnusedInspections` fails because a shortName is absent, print `profile.allTools.map { it.tool.shortName }` to confirm the actual registered names, and reconcile against `plugin.xml` (do not change the package-prefix logic — only the test's expected names if a name truly differs).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/analysis/WebdxInspectionRunner.kt src/test/kotlin/com/webdx/analysis/WebdxInspectionRunnerTest.kt
git commit -m "feat(analysis): WebdxInspectionRunner runs all plugin inspections project-wide"
```

---

### Task 2: Tool window panel + icon + registration

**Files:**
- Create: `src/main/kotlin/com/webdx/analysis/WebdxAnalysisToolWindowFactory.kt`
- Create: `src/main/resources/icons/webdx13.svg`
- Modify: `src/main/resources/META-INF/plugin.xml` (add `<toolWindow>` inside the existing `<extensions defaultExtensionNs="com.intellij">` block)
- Test: `src/test/kotlin/com/webdx/analysis/WebdxAnalysisToolWindowFactoryTest.kt`

**Interfaces:**
- Consumes: `WebdxInspectionRunner.runAll(project)` from Task 1; IntelliJ Platform `com.intellij.openapi.wm.ToolWindowFactory`, `ToolWindow`, `com.intellij.ui.content.ContentFactory`, `com.intellij.openapi.project.DumbService`, `com.intellij.openapi.ui.Messages`.
- Produces: `class WebdxAnalysisToolWindowFactory : ToolWindowFactory` with `createToolWindowContent(project, toolWindow)`; the run button is created by `fun buildPanel(project: Project): javax.swing.JComponent` (visible for testing), and the button is named `"webdx.runAnalysis"` (via `component.name`) so a test can find it.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/webdx/analysis/WebdxAnalysisToolWindowFactoryTest.kt`:

```kotlin
package com.webdx.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComponent

class WebdxAnalysisToolWindowFactoryTest : BasePlatformTestCase() {

    fun testPanelHasRunButton() {
        val panel = WebdxAnalysisToolWindowFactory().buildPanel(project)
        val button = findButton(panel)
        assertNotNull("panel must contain the run button", button)
        assertEquals("webdx.runAnalysis", button!!.name)
    }

    private fun findButton(c: Container): JButton? {
        for (child in c.components) {
            if (child is JButton && child.name == "webdx.runAnalysis") return child
            if (child is Container) findButton(child)?.let { return it }
        }
        return null
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.webdx.analysis.WebdxAnalysisToolWindowFactoryTest"`
Expected: FAIL — `WebdxAnalysisToolWindowFactory` is unresolved (compilation error).

- [ ] **Step 3: Write the icon**

`src/main/resources/icons/webdx13.svg` (13×13, themed via `currentColor`; a magnifier over a list — "scan the project"):

```xml
<svg width="13" height="13" viewBox="0 0 13 13" fill="none" xmlns="http://www.w3.org/2000/svg">
  <path d="M1.5 2.5h5M1.5 5h3.5M1.5 7.5h2.5" stroke="currentColor" stroke-width="1" stroke-linecap="round"/>
  <circle cx="8.5" cy="7.5" r="2.6" stroke="currentColor" stroke-width="1.1"/>
  <path d="M10.4 9.4 12 11" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/>
</svg>
```

- [ ] **Step 4: Write the factory implementation**

`src/main/kotlin/com/webdx/analysis/WebdxAnalysisToolWindowFactory.kt`:

```kotlin
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
```

- [ ] **Step 5: Register the tool window in plugin.xml**

In `src/main/resources/META-INF/plugin.xml`, inside the existing `<extensions defaultExtensionNs="com.intellij">` block (e.g. right after the opening tag at line ~25), add:

```xml
        <toolWindow id="WebDX"
                    anchor="left"
                    icon="/icons/webdx13.svg"
                    factoryClass="com.webdx.analysis.WebdxAnalysisToolWindowFactory"/>
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "com.webdx.analysis.WebdxAnalysisToolWindowFactoryTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/webdx/analysis/WebdxAnalysisToolWindowFactory.kt \
        src/main/resources/icons/webdx13.svg \
        src/main/resources/META-INF/plugin.xml \
        src/test/kotlin/com/webdx/analysis/WebdxAnalysisToolWindowFactoryTest.kt
git commit -m "feat(analysis): left-stripe WebDX tool window with run-analysis button"
```

---

### Task 3: Full build + regression + docs

**Files:**
- Modify: `README.md` (add a short feature bullet under the feature list)
- Modify: `CHANGELOG.md` (new version entry) — only if the file exists; check first

**Interfaces:**
- Consumes: everything from Tasks 1–2.
- Produces: documentation; no new code symbols.

- [ ] **Step 1: Full build to verify plugin loads and nothing regressed**

Run: `./gradlew buildPlugin test`
Expected: BUILD SUCCESSFUL; all tests pass (the new analysis tests plus the existing suite).

If `buildPlugin` reports a plugin-verifier/registration error for the `<toolWindow>` (e.g. icon path not found), confirm the icon is at `src/main/resources/icons/webdx13.svg` and the `icon` attribute is the resource-absolute path `/icons/webdx13.svg`.

- [ ] **Step 2: Add README feature bullet**

In `README.md`, add to the top "Feature areas" list a bullet:

```markdown
- **Project-wide analysis** — a WebDX button in the left tool-window stripe runs every
  plugin inspection (unused CSS-module classes, unused SCSS symbols, unused RN keys,
  dead exports/re-exports, plus the unknown-class/key and i18n checks) across the whole
  project in one pass; results open in the standard Inspection Results window.
```

- [ ] **Step 3: Add CHANGELOG entry (only if CHANGELOG.md exists)**

Run: `test -f CHANGELOG.md && echo EXISTS || echo NONE`

If `EXISTS`, add a new top entry following the file's existing format, e.g.:

```markdown
## [Unreleased]
### Added
- Project-wide analysis tool window: a left-stripe WebDX button runs all plugin
  inspections over the whole project; findings show in the standard Inspection Results window.
```

If `NONE`, skip this step.

- [ ] **Step 4: Commit**

```bash
git add README.md CHANGELOG.md 2>/dev/null; git commit -m "docs(analysis): document project-wide analysis tool window"
```

---

## Notes for the implementer

- `InspectionToolWrapper.getTool()` instantiates the inspection — acceptable here (one pass over the profile at build time).
- `doInspections` runs asynchronously and shows its own progress; the runner returns immediately. There is intentionally no re-entry lock (matches the platform's "Inspect Code").
- The button check uses `DumbService.isDumb`; running mid-index is unreliable, so we bail with a message rather than disabling/enabling reactively.
- Manual verification after build (not automatable here): open the project in a sandbox IDE (`./gradlew runIde`), click the WebDX icon in the left stripe, press the button, and confirm the Inspection Results window opens with our inspection groups populated.
