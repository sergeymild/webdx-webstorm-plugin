# WebDX unused-code analysis tool window — design

Date: 2026-06-24
Status: approved (ready for implementation plan)

## Goal

Add a one-click way to run **all of the plugin's own inspections across the whole
project** and review the findings in WebStorm's standard **Inspection Results** tool
window. Triggered from a WebDX button in the left tool-window stripe.

Today every inspection (unused CSS-module classes, unused SCSS symbols, unused RN
StyleSheet keys, dead exports, dead re-exports/barrels, plus the unknown-class /
unknown-key / i18n / override validation checks) only runs *on-the-fly per open
file*. There is no way to sweep the entire codebase for dead/unused code in one
pass. This feature provides that sweep.

## Decisions (from brainstorming)

- **Inspection set:** run **everything the plugin registers** — both the
  unused/dead inspections and the validation (unknown/override/i18n) ones. No other
  IDE inspections.
- **Trigger UI:** an icon in the **left** tool-window stripe. Clicking it opens a
  small WebDX panel containing a single prominent button. The button starts the run.
  (Tool windows open a panel on click; we deliberately do not try to make the stripe
  icon run analysis directly.)
- **Scope:** the **whole project**, immediately. No scope-selection dialog.
- **Results:** the platform's standard Inspection Results tool window, grouped by our
  inspections' `displayName` / `groupName`.

## Architecture

Three small units plus registration.

### 1. `WebdxInspectionRunner` (the core, UI-free)

`com.webdx.analysis.WebdxInspectionRunner`

`fun runAll(project: Project)` — builds a temporary in-memory inspection profile that
has **only this plugin's inspections enabled**, then runs a global inspection over
the project:

```
val profileManager = ProjectInspectionProfileManager.getInstance(project)
val profile = InspectionProfileImpl("WebDX project analysis",
                                    InspectionToolRegistrar.getInstance(),
                                    profileManager)
profile.initInspectionTools(project)
profile.disableAllTools(project)
ourShortNames(profile).forEach { profile.setToolEnabled(it, true, project) }

val managerEx = InspectionManager.getInstance(project) as InspectionManagerEx
val context = managerEx.createNewGlobalContext()
context.setExternalProfile(profile)
context.doInspections(AnalysisScope(project))
```

`doInspections` shows its own progress and populates the standard Inspection Results
window — we add no UI of our own for results.

**Selecting "our" inspections — by package, not a hard-coded list.** Iterate
`profile.getAllTools()`; for each `ScopeToolState`, enable it when its
`tool.tool` (the `InspectionProfileEntry` implementation) has a class in the
`com.webdx.` package. This auto-covers all ~40 registered shortNames (the same
inspection is registered once per language, each with its own shortName) and will
include any inspection added later without touching this code. Completion
contributors and other non-inspection extensions never appear in the profile's
tool list, so they are naturally excluded.

The temporary profile is never persisted and never replaces the user's current
profile.

### 2. `WebdxAnalysisToolWindowFactory`

`com.webdx.analysis.WebdxAnalysisToolWindowFactory : ToolWindowFactory`

Builds the panel shown when the stripe icon is clicked: a short description label and
a single button **"Запустить проверку проекта"**. The button's listener calls
`WebdxInspectionRunner.runAll(project)`. Layout is a simple top-aligned panel so the
button stays visible; the panel is intentionally minimal but leaves room to grow
(future filters/history).

### 3. Registration + icon

- `<toolWindow id="WebDX" anchor="left" icon="..." factoryClass="...WebdxAnalysisToolWindowFactory"/>`
  in `plugin.xml`.
- A monochrome **13×13** SVG at `resources/icons/webdx13.svg` (stripe-icon size),
  referenced by the toolWindow registration. Themed by the platform (uses
  `currentColor`-style stroke so it adapts to light/dark).

## Behaviour & edge cases

- **EDT:** the button click runs on the EDT, which is what `doInspections` requires.
- **Dumb mode:** while indexing is in progress, disable the button (or no-op with a
  message) — the inspections rely on indexes/PSI; running mid-index is unreliable.
- **Re-entry:** guard against a second run while one is in progress (track the active
  context / disable the button until it finishes).
- **No user-profile mutation:** we build and use a throwaway profile; the user's
  selected profile and settings are untouched, and nothing is written to disk.
- **Silent inspections:** inspections that stay silent when their context is
  absent (e.g. i18n unknown-key when no locale file is found) simply contribute
  nothing — no special handling needed.

## Testing

- **`WebdxInspectionRunner` profile selection (unit, `BasePlatformTestCase`):** after
  building the profile, the set of enabled tools is exactly the `com.webdx.*`
  inspections and contains no platform/third-party tools. This is the key invariant
  ("run our functionality, and only ours").
- **Smoke run:** a small fixture project containing a known-dead CSS-module class (or
  dead export) — running the runner reports at least one problem for it. (If driving
  the full `doInspections` UI flow is impractical under the test harness, assert at
  the tool-supplier level: the enabled unused-class tool, run over the fixture file,
  flags the dead class.)
- **Tool window panel:** `WebdxAnalysisToolWindowFactory` populates the tool window
  with content whose component contains the run button.

## Files

- `src/main/kotlin/com/webdx/analysis/WebdxInspectionRunner.kt` (new)
- `src/main/kotlin/com/webdx/analysis/WebdxAnalysisToolWindowFactory.kt` (new)
- `src/main/resources/icons/webdx13.svg` (new)
- `src/main/resources/META-INF/plugin.xml` (add `<toolWindow>` registration)
- `src/test/kotlin/com/webdx/analysis/WebdxInspectionRunnerTest.kt` (new)

## Out of scope (YAGNI)

- Category checkboxes / per-inspection toggles in the panel (the panel is built to
  allow them later, but MVP is one button).
- Custom results presentation — we reuse the platform window as-is.
- Auto-fix / batch cleanup of findings.
- Scope selection (module/directory/custom scope).
