# WebDX (WebStorm / IntelliJ plugin)

Developer-experience helpers for a React/TypeScript project, all resolved from the
actual source files on disk so they keep working **independently of the TypeScript
language service** — important here because this project runs on the experimental
**TypeScript-Go (tsgo)** engine, which does not load TS service plugins like
`typescript-plugin-css-modules`.

Feature areas:
- **CSS Modules** — scoped Find Usages, sibling-module auto-import, `styles.`
  class-name completion, unknown/unused-class inspections, full Sass `@import`-chain
  awareness (completion/inspections/Find Usages see inlined classes), an
  overrides-imported-class warning, go-to the effective `styles.<class>` declaration,
  and an Alt+Enter `@import` for name-resolved SCSS mixins/functions/vars/placeholders.
  Also recognises **"bam" classes** built from a string `$var` selector via `#{$var}`
  interpolation + `&`-BEM nesting (`#{$sidebar} { &__search {} }`), and **bracket access**
  `styles['kebab--class']` (completion inserts brackets for non-identifier names; go-to /
  inspections / Find Usages read them). A dynamic `styles[variant]` access never
  false-flags — when the key is computed, every class of that module counts as used.
  Cmd+Click on a class declaration shows its usages; a class reachable only dynamically
  (no static `styles.foo`) falls back to its `styles[variant]` application site, while a
  statically-used class lists only its real references (the dynamic site isn't mixed in).
- **i18n (`react-i18next`)** — translation-key completion, unknown-key inspection
  (i18next plural/ordinal-aware: a base `key` is valid when only `key_one`/`key_other`/… exist),
  go-to-definition + scoped Find Usages on a key, and interpolation tooling
  (option-object completion, checks, and go-to-placeholder) for `t('key', { … })`.
- **React Native `StyleSheet.create`** — on `styles.<key>` (and `const { key } = styles`):
  go-to the key declaration, scoped Find Usages, unknown-key and unused-key inspections,
  plus sibling auto-import of `styles`. Source-resolved (no TS service); covers inline,
  named `export const`, and `export default StyleSheet.create({…})` objects; static
  `styles['key']` counts as a use and a dynamic `styles[`a${x}`]` access never false-flags.
- **Barrel exports** — Alt+Enter on an exported component name re-exports it through every existing
  `index.ts(x)` up to the auto-detected module root (`package.json` / tsconfig-alias target / highest
  index below the `@/` source-root). Skips missing index files (adjusting the path), matches each
  barrel's style, handles default exports (`export { default as X }`), de-dups already-wired levels,
  and applies all edits as one undoable command. Never edits consumer files or creates index files.
- **Project-wide analysis** — a WebDX tool window in the left stripe with one button per
  check (unused CSS-module classes, unused SCSS symbols, unused/unknown RN keys,
  dead exports/re-exports, unknown CSS classes, i18n checks…) plus **Run all** and a
  **Stop** button. Each button runs only its inspection(s) across the whole project in one
  pass; results open in the standard Inspection Results window. Runs through the platform's
  own Inspect-Code pipeline, so the scan is fully cancellable.
- **Dead exports / dead barrels** — greys re-exports (`export … from`) and directly-declared
  exports that no real consumer reaches through the import/re-export graph (Next.js entry
  points excluded).
- **SCSS symbol usage** — project-wide unused inspection, Find Usages, and go-to for
  `$variables`, `@function`s, `@mixin`s, and `%placeholder`s, resolved through the real
  `@use`/`@import`/`@forward` graph (full transitivity, namespace-aware). Cmd+Click a
  declaration to Show Usages.

The plugin id stays `com.webdx.css-modules-scoped-usages` (kept for update
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
   (including via the `@/` tsconfig alias, transitively) are included too, and each
   entry's type-text shows the file that actually declares it (`common.module.scss`
   for an imported class), not the entry module.
   → `CssModuleStylesCompletion`, `CssModules.collectClassOrigins`

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

6a. **"bam" interpolated / `&`-nested BEM classes.**
   Classes produced by a string `$var` used as a selector (`$sidebar: '.sidebar';
   #{$sidebar} { &__search {} }` → `sidebar`, `sidebar__search`) have no `CssClass`
   node, so they were invisible. They are now resolved from source PSI and treated
   like real classes by completion, the unknown/unused inspections, go-to, and Find
   Usages. Plain literal-parent BEM (`.foo { &__bar {} }`) is covered too. The block
   variable may be local or imported from a directly `@import`/`@use`-d file (bare
   `#{$var}`, default-namespaced `#{vars.$var}`, aliased `#{v.$var}`). Out of scope:
   transitive/`@forward` reach, `@each`/`@for`, Sass maps, non-string values.
   → `BamSelectors`, `CssModules.collectClassNames`

### CSS module `@import` chains (Sass overrides & navigation)

Sass inlines an `@import`-ed `.module.scss` into the importing module's local scope,
so its classes are exported on `styles`. These features build on that:

- **"Overrides imported class" inspection.** A class in a `*.module.scss|css|sass|less`
  whose name is also declared in a module it (transitively) `@import`s is flagged with a
  WARNING naming the source file — e.g. `.nextButton` in `styles.module.scss` overriding
  `.nextButton` from `@import`-ed `common.module.scss`. Both rules apply (Sass inlines
  the import) and the local one wins the cascade; this surfaces the shadowing.
  → `CssModuleOverrideClassInspection`, `CssModules.importedClassOrigins`

- **Go-to-declaration on `styles.<class>` → the single effective declaration.**
  Cmd+Click / Cmd+B navigates to the **local override** when the importing module
  redefines an `@import`-ed class, else to the declaring file in the chain — instead of
  the TS-Go service's multi-target "Choose Declaration" popup. Implemented by
  **overriding the `GotoDeclaration` action** (`overrides="true"`), because under the
  TS-Go fork navigation bypasses the `gotoDeclarationHandler`/`directNavigationProvider`
  EPs (see gotcha #7). The resolver runs on `originalFile` (navigation hands a
  non-physical PSI copy whose `virtualFile` is null — gotcha #8).
  → `CssModuleGotoDeclarationAction`, `CssModuleClassNavigation`

- **Add `@import` for a name-resolved SCSS symbol (Alt+Enter).** On a `@include <mixin>`,
  a `@function` call, a `$variable`, or a `%placeholder` that resolves only by name
  (its defining file isn't imported), an intention adds `@import '<path>';` for the file
  that defines it — using the project's `@/` tsconfig alias when one matches
  (`@/styles/mixins.scss`), else a relative path. Backed by a cached project symbol index.
  → `CssModuleImportSymbolIntention`, `CssModules.scssSymbolIndex` / `scssDefinedSymbols`
  / `importSpecifierFor` / `importsTarget`

### i18n translation keys (`react-i18next`)

The project's translations live in a large nested locale JSON
(`src/lang/translations/en.json`, thousands of dot-path keys) under a single
`translation` namespace — too big/deep to type in TypeScript. These features give
key support at the IDE level instead, all reading one cached index of the JSON
(`com.webdx.i18n`). They act on `t('...')`, `i18next.t(...)`, `i18n.t(...)`, and
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

### React Native StyleSheet styles (`com.webdx.rnstyles`)

A "StyleSheet object" is a `StyleSheet.create({ … })` call; its top-level object-literal
properties are the style keys. All features resolve from source PSI (no TS service), so they
behave correctly where the service treats `styles.<key>` as an untyped member. Covers inline
`const styles = …`, named `export const styles` (consumed via `import { styles }`), and
`export default StyleSheet.create({ … })` (consumed via `import styles from './styles'`). A
static `styles['key']` counts as a use; a dynamic `styles[`a${x}`]`/`styles[v]` access is
treated as using all keys (so the unused inspection never false-flags such a binding).

12. **Go-to on `styles.<key>`** → the single key property in the `StyleSheet.create` object.
    Works for inline (`const styles = …`), imported (`import { styles } from './styles'`),
    aliased imports, and a destructured local (`const { title } = styles` → `title`). Folded
    into the `GotoDeclaration` action override (only one such override is allowed).
    → `RnStyles.resolveKeyProperty`, `CssModuleGotoDeclarationAction`

13. **Scoped Find Usages on a style key.** Lists only the real references (`<binding>.<key>` accesses and `const { key } = styles` destructuring) in the
    scope (the containing file for an inline object; the importer files for an exported one),
    instead of every same-named member in the project.
    → `RnStyleFindUsagesHandlerFactory`

14. **"Unknown style key" inspection.** `styles.doesNotExist` is flagged when `styles`
    resolves to a `StyleSheet.create` object and the key is absent. Only fires on a resolved
    StyleSheet binding, so unrelated member access is never redlined.
    → `RnStyleUnknownKeyInspection`

15. **"Unused style key" inspection.** A key never referenced (`<binding>.<key>` or via
    destructuring) in its scope is greyed as unused.
    → `RnStyleUnusedKeyInspection`

16. **Sibling auto-import (completion).** Typing `styles` offers an entry to
    `import { styles } from './styles'` when a sibling file in the same folder exports a
    `StyleSheet.create` binding of that name — inserted by direct document editing (no TS service),
    and only when no such binding is already available in the file.
    → `RnStyleImportCompletion`

### Dead re-exports / dead barrels (`com.webdx.deadexports`)

17. **"Dead re-export" inspection.** A re-export (`export { X } from './x'`,
    `export { default } from`, `export * from`) is greyed as unused when **no real
    consumer reaches that name** — not just directly, but transitively through chains of
    re-exports. A "real consumer" is any non-re-export reference (`import { X }`,
    `import * as`, `require(F)`, `require(F).X`, dynamic `import(F)`); an `export … from`
    that itself nobody consumes does not count. So a barrel that only forwards a symbol the
    actual code reaches by a different path (e.g. a deep `require('@/screens/.../Screen')`
    that bypasses the `index.ts`) is correctly flagged, even though every `export … from`
    link *looks* like a usage to the platform.

    Per **exported name** (so a 10-line barrel flags exactly the dead links), with the
    source-vs-exported name handled correctly for aliases (`export { Inner as Outer }` is
    keyed on `Outer`, the name consumers import). For `export * from './S'` the source module
    `S` is resolved and the link is live only when a real consumer imports a name `S` actually
    exports (or takes the whole namespace via `import * as` / `require`) — so a dead
    `export * from './SomeFun'` is flagged even inside a barrel kept alive by its other links.
    The reachability search uses the IDE's own module resolution, so `@/` path aliases and
    `require()` are followed automatically.

    **Conservative by design:** anything not positively identified as a re-export keeps the
    name live (`import * as`, whole-module `require(F)`, and any unresolvable/dynamic
    reference → all names stay live), so it errs toward silence rather than a false "dead".

    **Framework entry points.** Files Next.js loads by file-system convention — everything
    under `pages/`/`src/pages/` (incl. `pages/api`, `_app`, `_document`, `_error`) and App
    Router `app/` files with a reserved basename (`page`, `layout`, `route`, …) — have no
    explicit importer, so they (and any barrel reached only through them) are treated as
    always live and never flagged. Gated on a `next.config.*` so non-Next projects with a
    `pages/` folder are unaffected.
    → `DeadReExportInspection`, `DeadReExports` (reachability), `NextEntryPoints` (entry points)

18. **"Unused export" inspection** (`com.webdx.deadexports.DeadExportInspection`). Greys a
    **directly-declared** export — `export const/let/var`, `export function`, `export class`,
    `export default`, a local `export { x as y }`, or `export interface/type/enum` — when **no
    other module** reaches its exported name through the import/re-export graph (reusing the same
    reverse reachability as the dead re-export inspection). Same-file references (e.g.
    `SomeFun.displayName = 'SomeFun'`, or another local symbol) do not count as usage, so an
    export consumed only by a dead barrel link is flagged. Next.js page/app entry points are
    excluded. The `… from` re-export links themselves remain owned by the dead re-export
    inspection above.

### SCSS symbol usage (`com.webdx.scsssymbols`)

19. **"Unused SCSS symbol" inspection, Find Usages, and go-to** for `$variables`, `@function`s,
    `@mixin`s, and `%placeholder`s, resolved through the project's `@use`/`@import`/`@forward`
    graph (full transitivity, namespace-aware — `ns.$x`, `@include ns.m`, `#{$x}` all count). A
    declaration referenced nowhere is greyed; Find Usages on it lists every resolved reference;
    Cmd+Click a use jumps to its declaration. Resolution is precise across name collisions (a
    same-named symbol used elsewhere does not keep a dead one alive). Source-resolved (regex +
    `findElementAt`, no Sass-plugin dependency); graph reuses `CssModules.resolveImportPath`.
    → `ScssImportGraph`, `ScssSymbols`, `ScssUnusedSymbolInspection`,
    `ScssSymbolFindUsagesHandlerFactory`, `ScssSymbolGotoDeclarationHandler`

---

## Architecture / where each thing lives

```
src/main/kotlin/com/webdx/cssmodules/
  CssModuleShared.kt                  // shared helpers (CssModules object)
  CssModuleFindUsagesHandlerFactory.kt// feature 1
  CssModuleImportCandidates.kt        // features 2 + 3 (provider + filter)
  CssModuleStylesCompletion.kt        // feature 4
  CssModuleUnknownClassInspection.kt  // feature 5
  CssModuleUnusedClassInspection.kt   // feature 6
  BamSelectors.kt                     // feature 6a: interpolated/&-nested BEM resolver
  CssModuleOverrideClassInspection.kt // overrides-imported-class warning
  CssModuleClassNavigation.kt         // shared styles.<class> -> effective decl resolver
  CssModuleGotoDeclarationAction.kt   // overrides GotoDeclaration for styles.<class>
  CssModuleGotoDeclarationHandler.kt  // legacy go-to EP (kept; not on the TS-Go path)
  CssModuleDirectNavigationProvider.kt// directNavigation EP (kept; not on the TS-Go path)
  CssModuleImportSymbolIntention.kt   // Alt+Enter: @import a name-resolved SCSS symbol
  CssScopedStartup.kt                 // DIAGNOSTIC ONLY: startup + /tmp markers
src/main/kotlin/com/webdx/barrels/
  BarrelExports.kt                    // boundary detection, chain walk, path/form, dedup
  BarrelExportIntention.kt            // Alt+Enter: export through barrel modules
src/main/resources/META-INF/plugin.xml
```

> Note: `CssModuleGotoDeclarationHandler` and `CssModuleDirectNavigationProvider` are
> retained for non-tsgo setups but are NOT invoked under the TS-Go fork (it owns
> `styles.<class>` resolution); the working path is the `GotoDeclaration` action override.
> Temporary diagnostic logging (`[CSS-GOTOACTION]`/`[CSS-DIRECTNAV]`/`[CSS-NAV]`) is still
> in place — safe to strip for a clean release.

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
  `<binding>.<name>` across importers (and consumers up the `@import` chain).
- `collectClassOrigins(moduleFile)` / `importedClassOrigins(moduleFile)` → class name →
  declaring file (own-first); the latter excludes own classes (drives the override
  inspection and `styles.<class>` go-to).
- `scssDefinedSymbols(text)` / `scssSymbolIndex(project)` → SCSS `@mixin`/`@function`/
  `$var`/`%placeholder` names, and a cached project-wide name → defining-file index.
- `importSpecifierFor(project, fromDir, target)` / `importsTarget(scssFile, target)` →
  build an `@/`-alias (or relative) import specifier, and test whether a file already
  imports a target (used by the Alt+Enter `@import` intention).
- `prevMeaningfulLeaf` / `nextMeaningfulLeaf` → whitespace/comment-skipping leaf
  navigation, used to detect `<qualifier> . <member>` purely from tokens.

### Extension points used (`plugin.xml`)
| Feature | EP | Namespace |
|---|---|---|
| Find Usages | `findUsagesHandlerFactory` (`order="first"`) | `com.intellij` |
| Completion | `completion.contributor` (per JS/TS language, `order="first"`) | `com.intellij` |
| Unknown/Unused inspections | `localInspection` (per language) | `com.intellij` |
| Dead re-export inspection | `localInspection` (per JS/TS language) | `com.intellij` |
| Unused export inspection | `localInspection` (per JS/TS language) | `com.intellij` |
| Overrides-imported-class inspection | `localInspection` (`language="CSS"` once — covers dialects) | `com.intellij` |
| `styles.<class>` go-to | `<action id="GotoDeclaration" overrides="true">` (+ unused `gotoDeclarationHandler` / `lang.directNavigationProvider`) | `com.intellij` |
| `@import` a SCSS symbol | `intentionAction` | `com.intellij` |
| Export through barrel modules | `intentionAction` | `com.intellij` |
| SCSS unused symbol / Find Usages / go-to | `localInspection` (CSS) + `findUsagesHandlerFactory` + `gotoDeclarationHandler` | `com.intellij` |
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

7. **The TS-Go fork owns `styles.<class>` navigation — go-to EPs are bypassed.**
   `styles.nextButton` resolves to every same-named CSS declaration via the fork's
   own reference resolution; neither `gotoDeclarationHandler` nor
   `lang.directNavigationProvider` is invoked on click (verified: 0 invocations in
   idea.log). The only thing that intercepts it is **overriding the `GotoDeclaration`
   action** (`<action id="GotoDeclaration" overrides="true">`) — its `actionPerformed`
   runs for both Cmd+B and Cmd+Click (it `implements CtrlMouseAction`). Wrap the
   interception in try/catch and delegate to `super` for everything else so a failure
   can never break normal navigation.

8. **Navigation hands a non-physical PSI copy.** During go-to, `element.containingFile`
   can be a copy whose `virtualFile` is null — so resolving imports against it returns
   null. Resolve against `containingFile.originalFile` (physical file, same text).

9. **`localInspection` for CSS dialects: register on `language="CSS"` ONCE.** SCSS/SASS/
   LESS are dialects of CSS, so registering the same inspection per-dialect makes a
   `.scss` file match multiple registrations and report each problem 2–3×. A single
   `language="CSS"` registration covers all dialects exactly once.

### Not a plugin problem: CSS-module typing under tsgo
`styles.container` completion/types from the **TS service** break because tsgo
doesn't load `typescript-plugin-css-modules` (declared in `tsconfig.json` →
`plugins`). Workaround used in this project: switch the TypeScript version to the
**"TypeScript-Go JetBrains Fork"** (Settings → Languages & Frameworks →
TypeScript), which supports service-powered types. This plugin's features don't
depend on that — they read the `.module.scss` directly.

---

## Build

### Requirements

| Component | Version / path |
|---|---|
| JDK | 21 (e.g. `/opt/homebrew/opt/openjdk@21` on macOS) |
| WebStorm | installed locally, default `/Applications/WebStorm.app` |
| Gradle | use the bundled wrapper `./gradlew` (no separate install) |

The build compiles against the **locally-installed WebStorm**
(`local("/Applications/WebStorm.app")` in `build.gradle.kts`), so no multi-GB SDK
is downloaded. If WebStorm lives elsewhere, adjust that path.

### Build the plugin

From the repo root:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew clean buildPlugin
```

Output: `build/distributions/webdx-<version>.zip` (the `buildPlugin` task deletes
older `*.zip` in `build/distributions` first).

### On a different machine

1. Install JDK 21 + WebStorm 2026.1.
2. If WebStorm isn't at `/Applications/WebStorm.app`, adjust the `local(...)` path
   in `build.gradle.kts`.
3. Run the same build command.

If the WebStorm build differs, the bundled Kotlin metadata version may change —
bump `kotlin("jvm")` to match (currently **2.3.0**; see gotcha #6).

## Install

### One command (build + install into the local WebStorm)

```bash
  ./install-to-webstorm.sh
```

Builds the plugin and unpacks it straight into the installed WebStorm's plugins
directory — then **just restart WebStorm**. The script:

- auto-detects the newest `WebStorm*` config dir under
  `~/Library/Application Support/JetBrains/` (no version hardcoded — picks up
  e.g. `WebStorm2026.2` after an upgrade automatically);
- runs `./gradlew buildPlugin` and takes the freshest zip from
  `build/distributions/`;
- reads the plugin folder name from the zip, removes any previous install, and
  unpacks the new build into `<config>/plugins/`.

Override the target IDE config dir if auto-detection picks the wrong one:

```bash
  WEBSTORM_CONFIG_DIR="$HOME/Library/Application Support/JetBrains/WebStorm2026.1" ./install-to-webstorm.sh
```

> Note: the build itself still compiles against `/Applications/WebStorm.app`
> (`local(...)` in `build.gradle.kts`); the script only handles deployment.

### Manual (Install Plugin from Disk)

1. WebStorm → **Settings → Plugins**.
2. Gear icon **⚙ → Install Plugin from Disk…**
3. Pick the built `build/distributions/webdx-<version>.zip`.
4. **Restart** the IDE.

### Update to a new build

1. Bump the `version` in `build.gradle.kts` (e.g. `1.6.0` → `1.6.1`) so you can
   tell the new build apart.
2. Rebuild (`./gradlew clean buildPlugin`).
3. Install the new zip the same way (**Install Plugin from Disk…**) and restart.

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
on in-memory `BasePlatformTestCase` fixtures (no mocks). 139 tests, all green.

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
| `CssModuleInspectionTest` | unused-class + unknown-class + overrides-imported-class, via real highlighting |
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
| `CssScssImportPsiTest` | import path resolution (relative + `@/` alias), transitive `collectAllClassNames`, module import graph + reverse reachability, `importedClassOrigins` |
| `CssModuleGotoDeclarationTest` | `styles.<class>` resolves to the single effective decl (local override vs imported) via `CssModuleClassNavigation` |
| `CssModuleDirectNavigationTest` | the `DirectNavigationProvider` returns the same single target |
| `CssModuleNavRealShapeTest` | reproduces the real JSX `className={styles.x}` + `@/`-alias chain + override shape |
| `CssModuleImportSymbolIntentionTest` | Alt+Enter `@import` intention: alias import for mixin/variable; not offered when imported / unknown |
| `DeadReExportsTest` | `DeadReExports.Analyzer` reverse reachability: per-name liveness, transitive chains, cycles, `export *` source-aware liveness (named/namespace consumers, dead-among-live, re-export chains) |
| `DeadReExportInspectionTest` | dead re-export links flagged via real highlighting; aliases, partial barrels, `require`, Next.js entry points, `export *` dead-among-live |
| `DeadExportInspectionTest` | directly-declared exports (const/function/class, default, local `export { x as y }`, TS types) flagged when no external consumer; live/used not flagged; same-file-only use flagged; Next page default + `… from` re-export not flagged; anonymous default + multi-binding |
| `BarrelExportsTextTest` / `…PathTest` / `…ChainTest` / `…SymbolTest` / `…PlanTest` | `BarrelExports` logic: style/line/dedup, index/source-root/module-root, chain+specifier, caret symbol, full plan (intch + muse + default + already-wired) |
| `BarrelExportIntentionTest` | availability + apply via the real intention (intch star shape, default-export conversion; not offered in index/without barrel/already-wired/non-exported) |

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
- **Clean-release cleanup:** strip the diagnostic markers (`CssScopedStartup.kt`, the
  `/tmp` writes, the `[CSS-SCOPED]`/`[CSS-GOTOACTION]`/`[CSS-DIRECTNAV]`/`[CSS-NAV]`
  WARN logs), and drop the unused `CssModuleGotoDeclarationHandler` /
  `CssModuleDirectNavigationProvider` (not invoked under the TS-Go fork — the
  `GotoDeclaration` action override is the working path).
- `styles.<class>` go-to only intercepts the `GotoDeclaration` action; a non-standard
  keybinding/gesture bound to a *different* navigation action isn't covered.
- Quick-fix on the "Unknown CSS class" inspection (create the class in the module).
- Rename for `styles.foo` ↔ the CSS class.
- Collapse the per-dialect registrations of the unused/unknown inspections to a single
  `language="CSS"` each (same dialect-duplication risk as gotcha #9).
```
