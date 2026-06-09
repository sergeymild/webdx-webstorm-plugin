# Changelog

History of the WebDX plugin (WebStorm/IntelliJ), so the work can be picked up on
another machine. Display name **WebDX**; plugin id stays
`com.intch.css-modules-scoped-usages` (kept for update continuity). Built against the
locally-installed WebStorm (`local("/Applications/WebStorm.app")`), Kotlin 2.3.0,
JDK 21, IntelliJ Platform Gradle Plugin 2.6.0.

Design spec: [`docs/superpowers/specs/2026-06-09-i18n-key-support-design.md`](docs/superpowers/specs/2026-06-09-i18n-key-support-design.md).
Feature reference + build/test setup (incl. the extra bundled plugins the test
runtime needs): see [`README.md`](README.md).

Build a distributable zip: `./gradlew buildPlugin` → `build/distributions/webdx-<version>.zip`
(the task wipes older zips first). Run tests: `./gradlew test` (real WebStorm SDK,
`BasePlatformTestCase`). All features resolve from source files, so they work on the
tsgo engine where the TS language service doesn't load plugins.

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
  JSON (`com.intch.i18n`): key completion, "unknown translation key" inspection,
  go-to-definition + find-usages on a key. The key-source JSON is located from the
  i18n config (the `initReactI18next` file → its `import en from …`), with a
  `*/translations/en.json` convention fallback.
  (`I18nKeyIndex`, `I18nConfig`, `I18nKeys`, `I18nCallSites`, `I18nKeyReference`,
  `I18nUnknownKeyInspection`.)
- 69 tests.

## 1.0.x — earlier
- **CSS Modules** DX (`com.intch.cssmodules`): scoped Find Usages on a class,
  sibling-module auto-import (completion-driven, with module-derived binding names),
  `styles.` class-name completion, unknown/unused-class inspections. Plus the initial
  test suite and the documented test-environment setup (bundled plugins the test
  runtime must load: css, sass, less, JavaScript, json).
