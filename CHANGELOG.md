# Changelog

History of the WebDX plugin (WebStorm/IntelliJ), so the work can be picked up on
another machine. Display name **WebDX**; plugin id stays
`com.webdx.css-modules-scoped-usages` (kept for update continuity). Built against the
locally-installed WebStorm (`local("/Applications/WebStorm.app")`), Kotlin 2.3.0,
JDK 21, IntelliJ Platform Gradle Plugin 2.6.0.

Design specs: [`2026-06-09-i18n-key-support-design.md`](docs/superpowers/specs/2026-06-09-i18n-key-support-design.md),
[`2026-06-10-scss-import-inlined-classes-design.md`](docs/superpowers/specs/2026-06-10-scss-import-inlined-classes-design.md)
(+ plan `docs/superpowers/plans/2026-06-10-scss-import-inlined-classes.md`). Later CSS-module
work (override inspection, `styles.<class>` go-to, the `@import` intention) was iterated
directly and is recorded in the version entries below.
Feature reference + build/test setup (incl. the extra bundled plugins the test
runtime needs): see [`README.md`](README.md).

Build a distributable zip: `./gradlew buildPlugin` → `build/distributions/webdx-<version>.zip`
(the task wipes older zips first). Run tests: `./gradlew test` (real WebStorm SDK,
`BasePlatformTestCase`). All features resolve from source files, so they work on the
tsgo engine where the TS language service doesn't load plugins.

## 1.7.0 — 2026-06-13
- **New: React Native `StyleSheet.create` support** (`com.webdx.rnstyles`), source-resolved
  like the CSS-module features. On `styles.<key>` (and on a `const { key } = styles`
  destructured local): go-to the single key declaration, scoped Find Usages (only the real
  `<binding>.<key>` accesses, not every same-named member), an "unknown style key" inspection,
  and an "unused style key" inspection (greys a `StyleSheet.create` key never referenced in its
  scope — the containing file for an inline object, the importer files for an exported one).
  Covers inline `const styles = StyleSheet.create({…})` and `export const styles = …` consumed
  via `import { styles } from './styles'`. Go-to is folded into the existing `GotoDeclaration`
  action override (the platform allows only one). No completion; no composition/spread or
  default-export styles (none exist in the target codebase).
  (`RnStyles`, `RnStyleUnknownKeyInspection`, `RnStyleUnusedKeyInspection`,
  `RnStyleFindUsagesHandlerFactory`.)

## 1.6.0 — 2026-06-10
- **New Alt+Enter intention: add `@import` for a name-resolved SCSS symbol.** When a
  `@include <mixin>`, a `@function` call, a `$variable`, or a `%placeholder` resolves
  only by name (its defining file isn't imported), the intention offers to add
  `@import '<path>';` for the file that defines it. The path uses the project's `@/`
  tsconfig alias when one matches (e.g. `@/styles/mixins.scss`), else a relative path.
  Backed by a cached project symbol index. (`CssModuleImportSymbolIntention`,
  `CssModules.scssDefinedSymbols`/`scssSymbolIndex`/`importSpecifierFor`/`importsTarget`.)
  139 tests.

## 1.5.1 — 2026-06-10
- **Fix: override warning shown three times.** The inspection was registered for
  SCSS/SASS/LESS/CSS separately, but those are dialects of CSS, so a `.scss` file
  matched three registrations and fired the warning thrice. Registered once on
  `language="CSS"` (covers all dialects exactly once).

## 1.5.0 — 2026-06-10
- **New inspection: "overrides imported class".** A class in a `*.module.scss|css|sass|less`
  whose name is also declared in a module it (transitively) `@import`s is flagged with a
  WARNING — e.g. `.nextButton` in `styles.module.scss` overriding `.nextButton` from the
  `@import`-ed `common.module.scss`. Message names the source file. Both rules apply (Sass
  inlines the import) and the local one wins the cascade; this surfaces the shadowing.
  (`CssModuleOverrideClassInspection`, `CssModules.importedClassOrigins`.) 133 tests.

## 1.4.9 — 2026-06-10
- **Override the `GotoDeclaration` action for `styles.<class>`.** Logs proved the
  TS-Go service bypasses BOTH `gotoDeclarationHandler` and `directNavigationProvider`
  (0 invocations on click) and resolves the member to every same-named CSS
  declaration. So we now override the `GotoDeclaration` action itself
  (`overrides="true"`): if the caret is on a `styles.<class>` we can resolve to a
  single effective declaration (local override wins), we navigate there and stop;
  otherwise we delegate to the platform default. Wrapped in try/catch so it can never
  break normal navigation. `[CSS-GOTOACTION]` diagnostic log kept for one round.

## 1.4.8 — 2026-06-10
- **Fix: `styles.<class>` go-to resolves against `originalFile`.** The provider IS
  invoked in the IDE (logs confirm) but `resolveTarget` returned null on the real
  file: during navigation the platform hands a non-physical PSI **copy** whose
  `virtualFile` is null, so `resolveModuleForBinding` couldn't find the import dir.
  Now it resolves via `containingFile.originalFile`. Reproduced the real shape (JSX
  `className={styles.nextButton}` + relative import + `@/`-alias `@import` chain +
  local override) in `CssModuleNavRealShapeTest`. 129 tests.

## 1.4.7 — 2026-06-10
- **Step-by-step `[CSS-NAV]` bail logging in the resolver** (temporary). Logs confirm
  `DirectNavigationProvider`/`GotoDeclarationHandler` ARE invoked for `styles.nextButton`
  but `resolveTarget` returns null in the real file; this pinpoints which step fails
  (qualifier / `resolveModuleForBinding` / `collectClassOrigins` / CssClass lookup).

## 1.4.6 — 2026-06-10
- **Verbose `[CSS-DIRECTNAV]` entry logging** (temporary) — logs the actual element
  shape (class, leaf?, text, prev leaf, parent) the platform passes to
  `DirectNavigationProvider.getNavigationElement` for `styles.nextButton`, to find
  out whether the provider is invoked at all and why it isn't winning over the
  TS-Go service's two-target resolution.

## 1.4.5 — 2026-06-10
- **Go-to via `DirectNavigationProvider` (the right hook).** Research showed the
  modern Ctrl+Click / Ctrl+B path (`GotoDeclarationOrUsageHandler2`) consults
  `com.intellij.lang.directNavigationProvider` FIRST and a non-null result
  short-circuits the platform's Symbol navigation — which is what the TS-Go service
  uses to offer every same-named CSS declaration. So `styles.<class>` now navigates
  to the single effective declaration (local override, else `@import` source). The
  legacy `gotoDeclarationHandler` was never on this path (0 invocations in idea.log),
  which is why 1.4.3 had no effect. Shared resolver: `CssModuleClassNavigation`.
  `resolveModuleForBinding` is now alias-aware (`@/…` imports) via `resolveImportPath`.
  Keeps `[CSS-DIRECTNAV]`/`[CSS-GOTO]` diagnostic logs for one more verification round.
- 127 tests.

## 1.4.4 — 2026-06-10
- **Diagnostic logging in the go-to-declaration handler** (temporary). Every
  invocation on a JS-like file logs `[CSS-GOTO]` lines to idea.log showing whether
  the handler ran, where it bailed (e.g. `resolveModuleForBinding` returns null for
  a non-relative `@/` alias import), or which target it resolved. To investigate why
  `styles.<class>` navigation isn't behaving as expected. Remove once diagnosed.

## 1.4.3 — 2026-06-10
- **Go-to-declaration on `styles.<class>` targets the effective declaration.** A new
  `CssModuleGotoDeclarationHandler` resolves the member to a single CSS class: when
  the importing module redefines an `@import`-ed class, navigation lands on the
  **local override** (the cascade winner), not the imported source; otherwise on the
  declaring file in the chain. Resolved from source PSI via `collectClassOrigins`.
  Caveat: the platform merges go-to targets from all providers, so if the TS service
  also resolves `styles.<class>` its targets still appear alongside this one.
- 124 tests.

## 1.4.2 — 2026-06-10
- **`styles.` completion shows each class's real source file.** The type-text on the
  right of every completion entry is now the file that actually declares the class —
  so an `@import`-inlined class reads as e.g. `common.module.scss`, not the entry
  module — instead of labelling everything with the entry module's name.
  (`CssModules.collectClassOrigins`; `collectAllClassNames` derives from it.)
- 120 tests.

## 1.4.1 — 2026-06-10
- **Scoped Find Usages follows the `@import` chain.** Find Usages / Cmd+Click on a
  class in a shared module (e.g. `common.module.scss`, consumed via Sass `@import`)
  now lists the real `styles.<class>` references in the JS/TS files that import it
  through the chain — instead of matching unrelated same-named `.class {`
  declarations and `@extend`s. (`CssModuleFindUsagesHandlerFactory` reuses
  `modulesTransitivelyImporting` + `findImporters`, JS-only with a binding qualifier.)
- 119 tests.

## 1.4.0 — 2026-06-10
- **CSS Modules: follow Sass `@import`.** Classes Sass inlines into a module via
  `@import` / `@use` / `@forward` (relative paths and the `@/` tsconfig alias,
  transitively) now count as the module's own: `styles.<imported>` completes, is
  not flagged unknown, and — on the imported file — is not greyed as unused when a
  consumer references it through the chain. Real dead code in a shared file is still
  flagged. (`CssModules.scssImportPaths`/`tsconfigAliases`/`resolveImportPath`/
  `collectAllClassNames`/`moduleImportGraph`/`modulesTransitivelyImporting`, widened
  `collectUsedClassNames`.)
- 118 tests.

## 1.3.0 — 2026-06-09
- **i18n go-to-placeholder.** Cmd+Click an option-object key (`t('k', { price: … })`)
  jumps into the locale JSON with the caret on the matching `{{price}}` inside the
  key's value. Non-placeholder keys (reserved options, typos) don't navigate.
  (`I18nPlaceholderReference`, `I18nOptions.keyForOptionProperty`.)
- **Build:** `buildPlugin` now deletes old `*.zip` in `build/distributions` first.
- Renamed display name → **WebDX** (was "CSS Modules Scoped Usages"); gradle
  rootProject → `webdx` (artifact is now `webdx-<version>.zip`).
- 96 tests.

## 1.2.1 — 2026-06-09
- **i18n scoped Find Usages.** Find Usages / Cmd+Click on a translation-key property
  in a locale JSON lists only the `t('key')` / `<Trans i18nKey>` references that
  resolve to that exact key — filtering out same-named properties and the JSON
  plugin's cross-locale links. Works from any sibling locale file.
  (`I18nKeyFindUsagesHandlerFactory`, `I18nKeys.pathOf`.)
- 93 tests.

## 1.2.0 — 2026-06-09
- **i18n interpolation tooling** for `t('key', { … })`: completion of the option
  object with the key's `{{placeholders}}`; warnings on unknown option key (typo),
  missing required placeholder, or a missing options object. Auto-provided
  `interpolation.defaultVariables` (parsed from the config) and reserved i18next
  options are never flagged; `...spread`/computed keys disable the missing check.
  (`I18nInterpolationInspection`, `I18nOptionsCompletion`, `I18nOptions`,
  `I18nKeys.placeholdersOf`, `I18nConfig.defaultVariableNames`.)
- 90 tests.

## 1.1.0 — 2026-06-09
- **i18n key support (`react-i18next`)**, all reading one cached index of the locale
  JSON (`com.webdx.i18n`): key completion, "unknown translation key" inspection,
  go-to-definition + find-usages on a key. The key-source JSON is located from the
  i18n config (the `initReactI18next` file → its `import en from …`), with a
  `*/translations/en.json` convention fallback.
  (`I18nKeyIndex`, `I18nConfig`, `I18nKeys`, `I18nCallSites`, `I18nKeyReference`,
  `I18nUnknownKeyInspection`.)
- 69 tests.

## 1.0.x — earlier
- **CSS Modules** DX (`com.webdx.cssmodules`): scoped Find Usages on a class,
  sibling-module auto-import (completion-driven, with module-derived binding names),
  `styles.` class-name completion, unknown/unused-class inspections. Plus the initial
  test suite and the documented test-environment setup (bundled plugins the test
  runtime must load: css, sass, less, JavaScript, json).
