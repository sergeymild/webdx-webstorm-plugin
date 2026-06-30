<div align="center">

<img src="src/main/resources/icons/webdx13.svg" width="84" alt="WebDX logo" />

# WebDX — React DX for WebStorm & IntelliJ

**CSS Modules, react-i18next, React Native styles, barrel exports & dead-code tools that keep working — even when the TypeScript language service doesn't.**

[![WebStorm / IntelliJ 2024.3+](https://img.shields.io/badge/WebStorm%20%2F%20IntelliJ-2024.3%2B-000000?logo=intellijidea&logoColor=white)](https://www.jetbrains.com/webstorm/)
[![Version](https://img.shields.io/badge/version-1.11.2-3DDC84)](CHANGELOG.md)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](build.gradle.kts)
[![Tests](https://img.shields.io/badge/tests-139%20passing-brightgreen)](docs/ARCHITECTURE.md#tests)
![React](https://img.shields.io/badge/React-61DAFB?logo=react&logoColor=black)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?logo=typescript&logoColor=white)
![Sass](https://img.shields.io/badge/Sass-CC6699?logo=sass&logoColor=white)

⭐ **If this saves you from a single broken `styles.` autocomplete, give it a star — it helps other React devs find it.**

</div>

---

## The problem

If your React/TypeScript project runs on the experimental **TypeScript-Go (`tsgo`)** engine, the IDE's TypeScript service stops loading service plugins like `typescript-plugin-css-modules`. The moment that happens, the everyday tooling you rely on quietly breaks:

- `styles.` autocomplete returns `any`-typed garbage instead of your real class names.
- Find Usages on a CSS class scans **every** `styles.foo` in the repo, not the one you clicked.
- Typos in `styles.doesNotExist`, `t('wrong.key')` or `styles.unknownRnKey` slip through with no warning.
- Dead `index.ts` re-exports and never-imported exports pile up invisibly.

**WebDX fixes all of it by resolving everything straight from your source files on disk** — the `.module.scss`, the locale JSON, the `StyleSheet.create({…})` object, the barrel chain. No type service required, so it works on `tsgo`, on the JetBrains TS-Go fork, and on the classic TS service alike.

> Not on `tsgo`? You still get scoped Find Usages, dead-code detection, SCSS-symbol navigation, barrel auto-export and i18n tooling that the IDE doesn't ship out of the box.

---

## ✨ Features

### 🎨 CSS Modules (`*.module.scss`)
- **`styles.` completion** with your real class names — including classes pulled in through Sass `@import` / `@use` / `@forward` chains (transitively, via the `@/` tsconfig alias), each labelled with the file that actually declares it.
- **Scoped Find Usages** on a class — searches only the files that import *that exact module*.
- **Sibling auto-import** — type `styles`, pick a sibling module, and the import line + the right binding name are inserted in one step.
- **Inspections:** unknown class (error), unused class (greyed), and "overrides an `@import`-ed class" (warning that names the shadowed file).
- **Go-to-declaration** on `styles.<class>` jumps to the single effective declaration (the local override, or the source file up the chain) — no multi-target popup.
- **Alt+Enter `@import`** for a name-resolved SCSS `@mixin` / `@function` / `$var` / `%placeholder`.
- Understands **BEM interpolation** (`#{$sidebar} { &__search {} }`) and **bracket access** (`styles['kebab--class']`).

### 🌍 i18n (`react-i18next`)
- **Translation-key completion** for every dot-path key in your locale JSON.
- **Unknown-key inspection** (plural/ordinal-aware: a base `key` is valid when only `key_one` / `key_other` exist).
- **Go-to-definition + scoped Find Usages** on a key — only the `t('key')` / `<Trans i18nKey>` sites that resolve to that exact key.
- **Interpolation tooling** for `t('key', { … })`: completes the option object from the key's `{{placeholders}}`, warns on unknown/missing variables, and Cmd+Click jumps to the `{{placeholder}}` in the value.

### 📱 React Native (`StyleSheet.create`)
- Go-to, scoped Find Usages, and **unknown-key / unused-key inspections** on `styles.<key>`.
- **Sibling auto-import** of a `StyleSheet` binding.
- Source-resolved across inline, `export const`, and `export default StyleSheet.create({…})` shapes.

### 🛢️ Barrel exports
- **Alt+Enter on an exported component** re-exports it through every existing `index.ts(x)` up to the auto-detected module root — matching each barrel's style, handling default exports, de-duping already-wired levels, all as one undoable edit.
- **Cmd+Click a re-export name** (`export { X } from './x'`) opens a popup whose **first entry jumps into the component itself** (the source declaration), and whose remaining entries are the **direct, single-hop** sites that draw `X` from *this* file — the next-level barrel that re-exports it and any module importing it straight from here. It does *not* chase `X` through further barrels to the leaf components that ultimately render it, so the result stays anchored to the file you clicked. Always a popup (even for a single result), so go-to-component and see-who-uses-it live in one gesture.

### 🪦 Dead-code detection
- **Dead re-export** and **unused export** inspections grey out names no real consumer reaches through the import/re-export graph (transitively, alias- and `export *`-aware). Next.js page/app entry points are excluded automatically.

### 🧬 SCSS symbols
- Unused-symbol inspection, Find Usages, and go-to for `$variables`, `@function`s, `@mixin`s, and `%placeholder`s — resolved through the real `@use` / `@import` / `@forward` graph (full transitivity, namespace-aware).

### 🔎 Project-wide analysis tool window
- A **WebDX** panel in the left stripe with one button per check, plus **Run all** and **Stop**. Each runs its inspection across the whole project through the platform's own cancellable Inspect-Code pipeline; results open in the standard Inspection Results window.

---

## 📦 Install

### From a built zip (recommended)

1. Build the plugin (see below) or grab a `webdx-<version>.zip` from your `build/distributions/`.
2. WebStorm → **Settings → Plugins** → ⚙ → **Install Plugin from Disk…**
3. Pick the zip and **restart** the IDE.

### One command (build + install into your local WebStorm)

```bash
./install-to-webstorm.sh
```

Builds the plugin and unpacks it straight into your installed WebStorm's plugins directory — then just restart. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#install) for options.

### Build from source

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew clean buildPlugin
# → build/distributions/webdx-<version>.zip
```

**Requirements:** JDK 21 · WebStorm installed locally (default `/Applications/WebStorm.app`) · the bundled `./gradlew` wrapper. The build compiles against your local WebStorm, so no multi-GB SDK download. Full build/test details: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

---

## ✅ Compatibility

| | |
|---|---|
| **IDEs** | WebStorm, IntelliJ IDEA Ultimate (and any IDE with the JavaScript + CSS plugins) |
| **Since build** | `243` (2024.3) — no upper bound |
| **Stack** | React · TypeScript / JavaScript · SCSS / Sass / LESS / CSS · react-i18next · React Native |
| **TS engine** | Classic TS service, JetBrains TS-Go fork, and experimental `tsgo` — features are source-resolved, so all three work |

Built and verified against **WebStorm 2026.1**.

---

## 🤝 Contributing

Issues and PRs are welcome. The codebase is Kotlin, fully unit-tested against the real IntelliJ engine (139 tests). Start with **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — it documents every feature's wiring, the extension points used, and the hard-won gotchas that explain why the code looks the way it does. The [CHANGELOG](CHANGELOG.md) tracks each release.

If WebDX is useful to you, the most helpful thing you can do is **⭐ star the repo and share it** — discoverability is what gets more eyes (and fixes) onto it.
