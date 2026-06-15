# Design: SCSS symbol usage — unused inspection, Find Usages, go-to

**Date:** 2026-06-15
**Status:** Approved (design)
**Component:** `com.webdx.scsssymbols` (new)

## Problem

SCSS files across the project declare `$variables`, `@function`s, `@mixin`s, and
`%placeholder`s. Symbols may be declared in **any** file and used (or not) from many other
files through the `@use`/`@import`/`@forward` graph. The IDE does not highlight project-wide
unused symbols, and declaration↔usage navigation is unreliable across that graph. The user
wants, for symbols declared anywhere:

1. an "unused" inspection greying declarations referenced nowhere;
2. **Find Usages** on a declaration → every reference site project-wide;
3. **go-to-declaration** from a use (`v.$x` / `$x` / `@include name` / `func(` / `%ph`).

The resolution must be **real** (respect import scope and namespaces), not "first file with that
name". The plugin already has `CssModules.scssDefinedSymbols(text)` (names declared in a file),
`scssSymbolIndex(project)` (name → first defining file), `resolveImportPath`, and bam's
`@use`/`@import` parser with namespaces (`BamSelectors.scssVarImports`) to build on.

## Verified facts (PSI probe + real-codebase survey)

- Reference forms: variable `$x` / `ns.$x` / `#{$x}` / `#{ns.$x}`; mixin `@include x` /
  `@include ns.x`; function `x(…)` / `ns.x(…)`; placeholder `@extend %x` / `@extend ns.%x`.
  The `ns.` namespace comes from `@use 'f' as ns` (or `@use 'f'` → default ns = basename).
- Real survey: `@mixin supports-safe-area-insets` is `@include`d in many files (NOT dead);
  `@function calcFluidFontSize` is referenced nowhere (dead). Detection is realistic.
- Declaration PSI (token → enclosing element):
  - variable: `SCSS_VARIABLE` (`$name`) under `SassScssVariableDeclarationImpl` (LHS);
  - function: name `CSS_FUNCTION_TOKEN` under `SassScssFunctionDeclarationImpl` (after `@function`);
  - mixin: name `CSS_IDENT` under `SCSSMixinDeclaration` (after `@mixin`);
  - placeholder: `%name` under `SassScssPlaceholderSelectorImpl` inside a ruleset (NOT under `@extend`).
- Reference PSI: variable `$name` `SCSS_VARIABLE`; function call name `CSS_FUNCTION_TOKEN` under
  `CssFunctionImpl`; `@include` (`SCSS_INCLUDE`) + name; `@extend` (`SCSS_EXTEND`) + `%name`.

## Approach: resolution-based with full transitivity

One resolution core feeds all three features: resolve a **use** to the **declaration** it
references, by honoring Sass import scope. Applying it to every use in the project yields the
full use→declaration graph, from which go-to, Find Usages, and unused all derive.

### Sass scope model (full transitivity)
For a file `F`:
- **`provided(G)`** = the declarations a file exposes to anyone who `@use`s/`@forward`s `G`:
  `G`'s own declarations ∪ the transitive closure over `G`'s `@forward` (and `@import`) edges.
  Cycle-safe.
- **Global (bare) scope of `F`** = files whose symbols `F` can reference with a **bare** name:
  `F` itself ∪ transitive closure over `F`'s `@import` edges (legacy `@import` leaks globally and
  transitively) ∪ `provided(T)` for each `@use T as *`.
- **Namespaced scope of `F`** = map `ns → provided(T)` for each `@use T as ns` (alias or default
  basename namespace).

### Resolution
`resolve(use)` where `use = (file F, namespace ns?, name N, kind K)`:
- if `ns` present: search `provided(namespaceTarget(F, ns))` for a declaration of `(N, K)`;
- else (bare): search the global scope of `F` for `(N, K)`.
On an in-scope collision (same name+kind declared in two reachable files) pick deterministically —
`F`-local first, else nearest by import order — and document it. Returns the declaring file +
declaration element, or null (unresolved → conservatively *not* treated as a use's anchor, and
the use is simply not attributed).

### Derived features
- **Go-to**: resolve the use under the caret → navigate to the declaration element.
- **Find Usages** of declaration `D`: every use `U` in the project with `resolve(U) == D`.
- **Unused**: a declaration `D` is unused iff no use in the project resolves to it (excluding its
  own declaration). This is precise across name collisions, unlike a name-only index.

Rejected: name-based first-wins (imprecise go-to/usages on repeated names — the user's objection)
and platform reliance (no project-wide SCSS analysis, not extensible).

Documented limitations: `@import` transitivity and `@forward` chains are modeled, but exotic Sass
edges (`@forward` with `show`/`hide`/`as prefix-*`, configured `@use … with`) are approximated by
ignoring the filter/prefix (may over-include scope → at worst a symbol is treated as *used*, never
a false "unused"). Conditional `@if`-guarded references are seen as uses (text-level).

## Components

New package `src/main/kotlin/com/webdx/scsssymbols/`.

### `ScssImportGraph` (scope model)
Cached per project (`CachedValuesManager` + `PsiModificationTracker.MODIFICATION_COUNT`).
- Parses every `.scss`/`.sass` file's `@use` / `@import` / `@forward` (extends bam's
  `scssVarImports` with `@forward`) into edges with namespace info, via `resolveImportPath`.
- Exposes `provided(file)`, `globalScopeFiles(file)`, `namespaceTarget(file, ns)` with the
  transitive closures above (cycle-safe, memoized).

### `ScssSymbols` (declarations, references, resolution)
- `enum Kind { VARIABLE, FUNCTION, MIXIN, PLACEHOLDER }`.
- `data class Decl(name, kind, element, file)` / `data class Ref(name, kind, namespace?, element, file)`.
- `declarationsIn(file): List<Decl>` — via the verified declaration PSI element types.
- `referencesIn(file): List<Ref>` — every use, via reference regexes/PSI, capturing the optional
  namespace prefix and the use element.
- `resolve(ref): Decl?` — the resolution above, using `ScssImportGraph`.
- `referenceAt(element): Ref?` — classify a caret element as a use (for go-to).
- `usesByDeclaration(project): Map<Decl, List<Ref>>` — cached project-wide map (resolve every
  `Ref`), feeding the unused inspection and Find Usages.

Reference regexes (namespace `(?:[\w-]+\.)?` optional and captured):
- variable `X`: `(?<![\w-])(?:([\w-]+)\.)?\$X` not at a declaration LHS (`$X` not immediately
  followed by `\s*:` at a statement position);
- function `X`: `(?:([\w-]+)\.)?X\s*\(` excluding the `@function X(` declaration;
- mixin `X`: `@include\s+(?:([\w-]+)\.)?X\b`;
- placeholder `X`: `@extend\s+(?:([\w-]+)\.)?%X\b`.

### `ScssUnusedSymbolInspection` (`localInspection`, `language="CSS"` once)
For each `Decl` in the file, if it is absent from `usesByDeclaration(project)` keys (no resolved
use), register `LIKE_UNUSED_SYMBOL` on `decl.element` ("Unused SCSS <kind> '<name>'"). Registered
once on `language="CSS"` (covers SCSS/SASS dialects without 2–3× duplication — gotcha #9).

### `ScssSymbolFindUsagesHandlerFactory` (`findUsagesHandlerFactory`, `order="first"`)
`canFindUsages` true on a declaration name token. Runs in a `ReadAction`; reports a `UsageInfo`
per `Ref` in `usesByDeclaration(project)[decl]`. Mirrors `CssModuleFindUsagesHandlerFactory`.

### `ScssSymbolGotoDeclarationHandler` (`lang.gotoDeclarationHandler`)
On a use (`referenceAt` non-null), return `resolve(ref)?.element`. The standard go-to EP works for
`.scss` (the TS-Go fork only intercepts `.tsx` — gotcha #7), so no GotoDeclaration-action override.

## plugin.xml
Add (under `defaultExtensionNs="com.intellij"`):
- `localInspection` (language="CSS") → `ScssUnusedSymbolInspection`
- `findUsagesHandlerFactory` (order="first") → `ScssSymbolFindUsagesHandlerFactory`
- `lang.gotoDeclarationHandler` → `ScssSymbolGotoDeclarationHandler`

## Scope / non-goals (YAGNI)
In scope: `$var`, `@function`, `@mixin`, `%placeholder`; all reference forms; project-wide
`.scss`/`.sass`; full `@import`/`@use`/`@forward` transitive scope. Out of scope: `@forward`
`show`/`hide`/prefix filters and `@use … with` configuration (approximated — over-include, never
false "unused"); CSS custom properties (`--var`); unused function/mixin **parameters**; rename;
built-in Sass module functions (`math.div`, etc. — a `sass:*` namespace target is ignored).

## Testing (BasePlatformTestCase, real WebStorm SDK)
- `ScssImportGraphTest`: `provided` / `globalScopeFiles` / namespace resolution across
  `@use as ns`, `@use as *`, `@import` (transitive), `@forward` chains; cycle-safe.
- `ScssSymbolsTest`: `declarationsIn` (4 kinds, correct elements); `referencesIn` (bare, `ns.x`,
  `#{}`, `@include`, call, `@extend`); `resolve` picks the correct file across collisions and
  scope; `referenceAt`.
- Unused inspection (highlighting): an unreferenced `$var`/`@function`/`@mixin`/`%placeholder` is
  greyed; one referenced via `ns.x` / `@include` / interpolation / another file / same-file value
  is not; a same-named symbol used in file A does not unflag a genuinely-unused one in file B.
- Find Usages: lists exactly the resolved reference sites across files (not same-named uses that
  resolve elsewhere).
- Go-to: from `v.$x` / `$x` / `@include name` / `func(` / `%ph` lands on the resolved declaration.

## Docs
CHANGELOG entry + README section ("SCSS symbol usage") on implementation; bump version (1.9.0).
