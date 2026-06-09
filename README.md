# CSS Modules Scoped Usages (WebStorm / IntelliJ plugin)

A WebStorm plugin that makes CSS-module (`*.module.scss|css|less|sass`) ergonomics
work the way you'd expect in a React/TypeScript project — **independently of the
TypeScript language service** (important: this project runs on the experimental
**TypeScript-Go (tsgo)** engine, which does not load TS service plugins like
`typescript-plugin-css-modules`).

Built and verified against **WebStorm 2026.1 (build 261)**.

---

## Features

All features resolve everything from the actual `.module.scss` file on disk, so
they work regardless of how (or whether) the TS service types the import.

1. **Scoped "Find Usages" on a CSS class.**
   `Find Usages` (Alt+F7) on a class selector inside a `*.module.scss` searches
   **only the files that import that exact module**, instead of every
   identically-named `styles.foo` in the project.
   → `CssModuleFindUsagesHandlerFactory`

2. **Auto-import of a sibling CSS module (completion).**
   Typing `styles` offers a completion entry per sibling CSS module. Selecting one
   **renames what you typed to the chosen binding and inserts the import** in a
   single step. The binding is named after the module unless it's the component's
   own module: in `Profile.tsx`, `Profile.module.scss` → `import styles from
   './Profile.module.scss'`, while `Sidebar.module.scss` → renames `styles` to
   `sidebar` and adds `import sidebar from './Sidebar.module.scss'`
   (`user-profile.module.scss` → `userProfile`; non-identifier names fall back to
   `styles`). The import line is inserted by direct document editing, so it works
   without the TS service.
   → `CssModuleStylesCompletion` (entries + insert), `importBindingFor` (naming)

   The unresolved-reference quick fix (Alt+Enter) still offers the siblings too,
   but only *adds* an import — it can't rename the reference — so it keeps the
   typed name (`import styles from './Sidebar.module.scss'`) to avoid broken code.
   → `CssModuleImportCandidatesFactory`

3. **Clean import popup.**
   When a sibling CSS-module import is offered, the unrelated junk candidates
   (`import { styles } from 'next/dist/...'`, `node:util`, …) are filtered out.
   → `CssModuleImportFilterFactory`

4. **`styles.` member completion from the module.**
   After `styles.`, the popup shows the **real class names** from the imported
   module (`mobileWrapper`, `container`, …) and suppresses the `any`-typed garbage
   that tsgo returns.
   → `CssModuleStylesCompletion`

5. **"Unknown CSS class" inspection (JS/TS side).**
   `styles.doesNotExist` is highlighted as an error when the class is not defined
   in the imported module.
   → `CssModuleUnknownClassInspection`

6. **"Unused CSS class" inspection (CSS side).**
   A class declared in a `*.module.scss` that is never referenced as
   `styles.<class>` in any importing file is greyed out as unused.
   → `CssModuleUnusedClassInspection`

---

## Architecture / where each thing lives

```
src/main/kotlin/com/intch/cssmodules/
  CssModuleShared.kt                  // shared helpers (CssModules object)
  CssModuleFindUsagesHandlerFactory.kt// feature 1
  CssModuleImportCandidates.kt        // features 2 + 3 (provider + filter)
  CssModuleStylesCompletion.kt        // feature 4
  CssModuleUnknownClassInspection.kt  // feature 5
  CssModuleUnusedClassInspection.kt   // feature 6
  CssScopedStartup.kt                 // DIAGNOSTIC ONLY: startup + /tmp markers
src/main/resources/META-INF/plugin.xml
```

### `CssModules` (shared helpers)
The heart of the resolution logic, all on generic PSI (no TS service):
- `resolveModuleForBinding(jsFile, "styles")` → finds `import styles from '…'`,
  resolves the relative path to the `.module.scss` PsiFile.
- `cssModuleBindings(jsFile)` → `{ "styles" -> {class names} }` for every CSS
  module imported in a JS/TS file.
- `collectClassNames(moduleFile)` → all `CssClass` names in a module.
- `findImporters(moduleFile)` → files that import a module + the local binding
  name each uses.
- `collectUsedClassNames(moduleFile)` → class names actually referenced as
  `<binding>.<name>` across importers.
- `prevMeaningfulLeaf` / `nextMeaningfulLeaf` → whitespace/comment-skipping leaf
  navigation, used to detect `<qualifier> . <member>` purely from tokens.

### Extension points used (`plugin.xml`)
| Feature | EP | Namespace |
|---|---|---|
| Find Usages | `findUsagesHandlerFactory` (`order="first"`) | `com.intellij` |
| Completion | `completion.contributor` (per JS/TS language, `order="first"`) | `com.intellij` |
| Unknown/Unused inspections | `localInspection` (per language) | `com.intellij` |
| Auto-import candidate | `importCandidatesFactory` | `JavaScript` |
| Import popup filter | `importCandidatesFilterFactory` | `JavaScript` |

---

## Hard-won gotchas (read before changing things)

These each cost real debugging time; they are the reason the code looks the way
it does.

1. **`defaultExtensionNs`, not `defaultExtensionPointName`.**
   `<extensions defaultExtensionNs="com.intellij">` is the correct attribute.
   Using `defaultExtensionPointName` makes every extension in the block silently
   unresolved — the plugin "loads" but **none of its `<extensions>` run**
   (you'll see `unresolved extension .findUsagesHandlerFactory` in idea.log on
   dynamic load). `<applicationListeners>` is unaffected, which is misleading.

2. **Completion in `.tsx` is owned by the tsgo LSP server.**
   The `textDocument/completion` is handled by `TypeScriptGoLspServer`. A
   `completion.contributor` registered **without a `language`** is never invoked
   for this position. It must be registered explicitly per language — crucially
   **`"TypeScript JSX"`** (the language id of `.tsx`). To hide the LSP's
   `any`-typed garbage, the contributor runs `order="first"` and calls
   `result.runRemainingContributors(parameters) { /* drop */ }` to consume and
   discard everything else.

3. **`processElementUsages` runs without a read action.**
   Wrap PSI access in `ReadAction.compute { … }` or you get
   "Read access is allowed from inside read-action only".

4. **Don't resolve usages via the platform reference search for CSS classes.**
   The default Find-Usages path resolves each `styles.foo` candidate through the
   TS service, which (with tsgo) blocks under a read lock and **freezes the IDE
   for 30s+**. Feature 1 instead finds importer files cheaply (file-reference
   search on the module) and scans them with plain PSI — no type resolution.

5. **Default imports via `JSImportCandidateDescriptor`.**
   For `import styles from './x.module.scss'`:
   `JSImportCandidateDescriptor(SimpleModuleDescriptor(scssPsiFile, "./x.module.scss"),`
   `importedName=<binding>, exportedName=<binding>, ImportExportPrefixKind.IMPORT,`
   `ES6ImportPsiUtil.ImportExportType.DEFAULT)`.
   The **first** string arg is the local binding (what gets rendered). Passing
   `"default"` there produces a broken empty `import` (reserved word).

6. **Build against the locally-installed WebStorm.**
   `local("/Applications/WebStorm.app")` in `build.gradle.kts` avoids downloading
   a multi-GB SDK. Consequence: it pulls WebStorm's bundled Kotlin metadata
   (2.3.0), so the Kotlin Gradle plugin must be **2.3.0**. `buildSearchableOptions`
   is disabled (it launches a headless IDE that fails on the bundled JBR).

### Not a plugin problem: CSS-module typing under tsgo
`styles.container` completion/types from the **TS service** break because tsgo
doesn't load `typescript-plugin-css-modules` (declared in `tsconfig.json` →
`plugins`). Workaround used in this project: switch the TypeScript version to the
**"TypeScript-Go JetBrains Fork"** (Settings → Languages & Frameworks →
TypeScript), which supports service-powered types. This plugin's features don't
depend on that — they read the `.module.scss` directly.

---

## Build

Requires JDK 21 and WebStorm installed at `/Applications/WebStorm.app`.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew clean buildPlugin
```

Output: `build/distributions/css-modules-scoped-usages-<version>.zip`

On a different machine: install JDK 21 + WebStorm 2026.1, adjust the
`local(...)` path in `build.gradle.kts` if WebStorm lives elsewhere, then run the
same command. If the WebStorm build differs, the bundled Kotlin metadata version
may change — bump `kotlin("jvm")` to match (see gotcha #6).

## Install

WebStorm → **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick the zip
→ **Restart**. Bump the `version` in `build.gradle.kts` between iterations so you
can tell the new build apart.

## Debug during development

`runIde` launches a sandbox IDE, but it's easier to install into the real IDE and
read logs:
- idea.log: `~/Library/Logs/JetBrains/WebStorm2026.1/idea.log` — features log with
  the `[CSS-SCOPED]` prefix (WARN level).
- `CssScopedStartup.kt` also writes `/tmp/css-scoped-*.txt` marker files on
  startup / find-usages / completion. **This is diagnostic scaffolding** added to
  prove which extensions actually run — safe to delete once stable.

---

## Tests

The suite runs the real IntelliJ engine against the locally-installed WebStorm SDK
on in-memory `BasePlatformTestCase` fixtures (no mocks). 30 tests, all green.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew test
```

Report (HTML): `build/reports/tests/test/index.html`.

### Test wiring in `build.gradle.kts`
```kotlin
intellijPlatform {
    // … local("/Applications/WebStorm.app")
    testFramework(TestFrameworkType.Platform)
}
testImplementation("junit:junit:4.13.2")
```

### Gotcha: tests need MORE bundled plugins than the plugin `<depends>` on
The shipped `plugin.xml` only `<depends>` on `com.intellij.css` + `JavaScript`
(everything else is present in a real WebStorm). The **test runtime is a bare IDE**
that loads only what `bundledPlugins(...)` declares — so the test build must add:

| Bundled plugin id | Why it's needed in tests |
|---|---|
| `com.intellij.css` | plain CSS |
| `org.jetbrains.plugins.sass` | SCSS / SASS — **separate plugin**, not part of `com.intellij.css` |
| `org.jetbrains.plugins.less` | LESS |
| `JavaScript` | JS/TS/JSX/TSX PSI + import resolution |
| `com.intellij.modules.json` | **required dependency of `JavaScript`** — without it the JS plugin silently won't load (`module dependency 'intellij.json.backend' … missing`) |

Symptoms when one is missing: `.scss`/`.tsx` files parse as `PLAIN_TEXT`/`UNKNOWN`,
and helpers return empty. Verify in a throwaway test with
`PluginManagerCore.loadedPlugins` and
`FileTypeManager.getInstance().getFileTypeByExtension("scss")` → must be `SCSS`.

### What's covered
| Test class | Target |
|---|---|
| `CssModulesLogicTest` | pure filename predicates (`isModuleFileName`, `isJsLikeFileName`) |
| `CssModulesPsiTest` | `CssModules` PSI helpers (collect/resolve/bindings/importers/used) |
| `CssModuleInspectionTest` | unused-class (CSS) + unknown-class (JS/TS) via real highlighting |
| `CssModuleCompletionTest` | `styles.` completion + LSP-garbage suppression |
| `CssModuleAutoImportCompletionTest` | completion auto-import: module-named entry, rename + insert import, no dup |
| `CssModuleImportBindingTest` | `importBindingFor` binding-name derivation |
| `CssModuleFindUsagesTest` | scoped Find Usages (only files importing that exact module) |

### Partial gap: the Alt+Enter quick-fix path is not fixture-testable
The completion-driven auto-import (entry + rename + import insertion) IS fully
tested (`CssModuleAutoImportCompletionTest`), because the import is inserted by
direct document editing rather than the TS-service-backed import machinery.

What's NOT fixture-testable is the **Alt+Enter unresolved-reference quick fix**
(`CssModuleImportCandidatesFactory` / filter): the light fixture doesn't run the
TS service, so `styles` is never flagged unresolved and that quick-fix never
triggers (probed — no highlights, no quick-fixes). Verify it manually in the IDE.

---

## Possible next steps
- Strip the diagnostic markers (`CssScopedStartup.kt`, the `/tmp` writes, the
  `[CSS-SCOPED]` logs) for a clean release build.
- Quick-fix on the "Unknown CSS class" inspection (create the class in the module).
- Handle bracket access `styles['kebab-case']` for the inspections.
- Suppress the "unused" warning when a module is accessed dynamically
  (`styles[variable]`) in an importer.
- Go-to-declaration / rename for `styles.foo` ↔ the CSS class.
```
