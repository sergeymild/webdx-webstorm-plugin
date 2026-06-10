# WebDX (WebStorm / IntelliJ plugin)

Developer-experience helpers for a React/TypeScript project, all resolved from the
actual source files on disk so they keep working **independently of the TypeScript
language service** — important here because this project runs on the experimental
**TypeScript-Go (tsgo)** engine, which does not load TS service plugins like
`typescript-plugin-css-modules`.

Two feature areas:
- **CSS Modules** — scoped Find Usages, sibling-module auto-import, `styles.`
  class-name completion, unknown/unused-class inspections.
- **i18n (`react-i18next`)** — translation-key completion, unknown-key inspection,
  go-to-definition + scoped Find Usages on a key, and interpolation tooling
  (option-object completion, checks, and go-to-placeholder) for `t('key', { … })`.

The plugin id stays `com.intch.css-modules-scoped-usages` (kept for update
continuity); only the display name is **WebDX**.

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
   that tsgo returns. Classes pulled in by a Sass `@import` / `@use` / `@forward`
   (including via the `@/` tsconfig alias, transitively) are included too.
   → `CssModuleStylesCompletion`

5. **"Unknown CSS class" inspection (JS/TS side).**
   `styles.doesNotExist` is highlighted as an error when the class is not defined
   in the imported module. A class defined only in an `@import`-ed module is
   recognised (not flagged), since Sass inlines it into the importing module's
   `styles`.
   → `CssModuleUnknownClassInspection`

6. **"Unused CSS class" inspection (CSS side).**
   A class declared in a `*.module.scss` that is never referenced as
   `styles.<class>` in any importing file is greyed out as unused. A class in a
   shared module that is `@import`-ed elsewhere counts as used when a consumer
   references it through the chain, so it is not falsely greyed; genuinely dead
   classes are still flagged.
   → `CssModuleUnusedClassInspection`

### i18n translation keys (`react-i18next`)

The project's translations live in a large nested locale JSON
(`src/lang/translations/en.json`, thousands of dot-path keys) under a single
`translation` namespace — too big/deep to type in TypeScript. These features give
key support at the IDE level instead, all reading one cached index of the JSON
(`com.intch.i18n`). They act on `t('...')`, `i18next.t(...)`, `i18n.t(...)`, and
`<Trans i18nKey="...">`, only in files that import i18n, and skip dynamic
template-literal keys (`` t(`a.${x}`) ``).

7. **Translation-key completion.** Inside a key string, all valid dot-path keys
   are offered (`common.action.copy`, `page.pwa_install.title`, …).
   → `I18nKeyReference.getVariants` (the platform surfaces reference variants as
   completion for JS string literals — no separate contributor needed).

8. **"Unknown translation key" inspection.** A key string that isn't present in the
   locale JSON is flagged with a warning. Stays silent if no key file is located
   (so it never redlines the whole project).
   → `I18nUnknownKeyInspection`

9. **Go-to-definition + scoped Find Usages on a key.** Cmd/Ctrl+click a key to jump
   to its entry in `en.json`. Find Usages on a key property in any locale JSON lists
   **only the `t('key')` / `<Trans i18nKey>` references that resolve to that exact
   key** — not every property with the same name, and not the same key in sibling
   locale files (the JSON plugin links those, so they're filtered out). Works from
   any locale file (resolves to the canonical key source first).
   → `I18nKeyReference` / `I18nKeyReferenceContributor` /
   `I18nKeyFindUsagesHandlerFactory`

10. **Interpolation-variable completion + checks.** For `t('key', { … })`, the key's
   value `{{placeholders}}` (`step_label: "Step {{step}}"` → `step`) drive:
   - completion of the option object keys inside `{ <caret> }` → `I18nOptionsCompletion`;
   - warnings on an **unknown** option key (typo `{ stp }`), a **missing** required
     placeholder, or a missing options object entirely → `I18nInterpolationInspection`.
   Auto-provided `interpolation.defaultVariables` (parsed from the config:
   `specialistsCount`, `specialistsCountFull`) and reserved i18next options
   (`count`, `context`, `defaultValue`, …) are never flagged; `...spread` /
   computed keys disable the missing-key check. Applies to the call form only
   (not `<Trans>`).
   → `I18nOptions`, `I18nKeys.placeholdersOf`, `I18nConfig.defaultVariableNames`

11. **Go-to-placeholder from an option key.** Cmd/Ctrl+click an option-object key
   (`t('k', { price: … })`) to jump into the locale JSON with the caret on that
   `{{price}}` inside the key's value. Non-placeholder keys (reserved options,
   typos) don't navigate.
   → `I18nPlaceholderReference` (registered in `I18nKeyReferenceContributor`),
   `I18nOptions.keyForOptionProperty`

The key-source JSON is located **from the i18n config**: the file that imports
`initReactI18next` is found, and its `import en from '…'` is followed to the JSON.
If detection fails, it falls back to convention (`*/translations/en.json`).
→ `I18nConfig`, `I18nKeyIndex`, `I18nCallSites`, `I18nKeys`

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
- `scssImportPaths(text)` / `resolveImportPath(dir, project, path)` -> parse a
  module's `@import`/`@use`/`@forward` targets and resolve them (relative + `@/`
  tsconfig alias, via `tsconfigAliases`).
- `collectAllClassNames(moduleFile)` -> own classes plus every transitively
  `@import`-ed module's classes (cycle-safe, cached).
- `moduleImportGraph(project)` / `modulesTransitivelyImporting(moduleFile)` ->
  the CSS-module import graph and reverse reachability, used by the unused-class
  inspection to see usage through `@import` chains.
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
on in-memory `BasePlatformTestCase` fixtures (no mocks). 118 tests, all green.

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
| `I18nConfigLogicTest` | pure: `enImportPath` (follow the `en` import in the i18n config) |
| `I18nKeySourceTest` | locating the key-source JSON (config-driven + convention fallback) |
| `I18nKeysTest` | flatten JSON → leaf dot-path keys; resolve key → `JsonProperty` |
| `I18nKeyIndexTest` | cached index: keys + resolve, empty when no source |
| `I18nCallSitesTest` | the call-site matcher (`t`/`i18next.t`/`i18n.t`/`<Trans i18nKey>`, guards) |
| `I18nUnknownKeyInspectionTest` | unknown-key warning; quiet on known/dynamic; `<Trans>` |
| `I18nKeyReferenceTest` | go-to-definition (key → JSON) + key completion via the reference |
| `I18nPlaceholdersLogicTest` | pure: `{{placeholder}}` extraction (whitespace, `{{x, fmt}}`, empty) |
| `I18nDefaultVariablesLogicTest` | pure: parse `interpolation.defaultVariables` keys from config |
| `I18nInterpolationIndexTest` | index: a key's placeholders + the config's default variables |
| `I18nInterpolationInspectionTest` | unknown/missing interpolation variable, no-object, spread, reserved/default |
| `I18nOptionsCompletionTest` | completing the `t(key, { … })` object with placeholder names |
| `I18nKeyFindUsagesTest` | Find Usages on a key property → only the resolving code refs (cross-locale filtered) |
| `I18nPlaceholderNavigationTest` | Cmd+Click an option key → caret on the `{{placeholder}}` in the value |
| `CssScssImportLogicTest` | pure: SCSS `@import`/`@use`/`@forward` path parsing + tsconfig alias parsing |
| `CssScssImportPsiTest` | import path resolution (relative + `@/` alias), transitive `collectAllClassNames`, module import graph + reverse reachability |

> **JSX PSI note:** in a `.tsx`, a `<Trans/>` element is itself a `JSLiteralExpression`
> subtype (`JSXXmlLiteralExpression`), and the `i18nKey` value is an `XmlAttributeValue`
> (`com.intellij.lang.javascript.psi.e4x`), **not** a `JSLiteralExpression`. The matcher
> handles both element types; `keyOf` returns null for the JSX element itself.

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
