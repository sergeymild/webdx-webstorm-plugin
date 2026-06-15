# Design: SCSS symbol usage — unused inspection, Find Usages, go-to

**Date:** 2026-06-15
**Status:** Approved (design)
**Component:** `com.webdx.scsssymbols` (new)

## Problem

Shared SCSS files (e.g. `UI-KIT/PostComponent/module/vars.scss`) declare many `$variables`,
`@function`s, `@mixin`s, and `%placeholder`s. A large number are never referenced anywhere,
but the IDE does not highlight them as unused, and navigation between a symbol's declaration
and its uses is unreliable across the project's `@use`/`@import` graph. The user wants:

1. an "unused" inspection greying SCSS symbol declarations referenced nowhere;
2. **Find Usages** on a declaration → every reference site project-wide;
3. **go-to-declaration** from a use (`v.$x` / `$x` / `@include name` / `func(` / `%ph`).

The plugin already has half the infrastructure: `CssModules.scssDefinedSymbols(text)` (names
declared in a file) and `CssModules.scssSymbolIndex(project)` (name → first defining file),
used by the Alt+Enter `@import` intention.

## Verified facts (PSI probe + real-codebase survey)

- Symbols are referenced in these forms: variable `$x`, namespaced `ns.$x`, interpolation
  `#{$x}` / `#{ns.$x}`; mixin `@include x` / `@include ns.x`; function `x(…)` / `ns.x(…)`;
  placeholder `@extend %x` / `@extend ns.%x`. The `ns.` namespace comes from `@use 'f' as ns`.
- Real survey: `@mixin supports-safe-area-insets` is `@include`d in many files (NOT dead);
  `@function calcFluidFontSize` is referenced nowhere (dead). Detection is realistic.
- Declaration PSI (token → enclosing element):
  - variable: `SCSS_VARIABLE` (`$name`) under `SassScssVariableDeclarationImpl`;
  - function: name `CSS_FUNCTION_TOKEN` under `SassScssFunctionDeclarationImpl` (after `@function`);
  - mixin: name `CSS_IDENT` under `SCSSMixinDeclaration` (after `@mixin`);
  - placeholder: `%name` under `SassScssPlaceholderSelectorImpl` inside a ruleset (NOT under `@extend`).
- Reference PSI: variable `$name` `SCSS_VARIABLE`; function call name `CSS_FUNCTION_TOKEN` under
  `CssFunctionImpl`; `@include` (`SCSS_INCLUDE`) + name; `@extend` (`SCSS_EXTEND`) + `%name`.

## Approach

**Name-based, project-wide, conservative** — consistent with `scssSymbolIndex` and the
dead-export inspection's "err toward silence" stance:

- A cached project-wide index of every **referenced** symbol name (per kind), built by a regex
  scan of all `.scss`/`.sass` file texts (cheap; `VfsUtilCore.loadText`, like `scssSymbolIndex`).
- A symbol declaration is **unused** iff its name does not appear in the referenced-name set for
  its kind (excluding its own declaration). The namespace prefix (`ns.`) is stripped, so a
  reference matches by bare name regardless of how it's imported.
- "Used" counts a reference **anywhere**, including the declaring file itself (e.g. `$base` used
  in another `$var`'s value) — only the declaration position itself is excluded.

Rejected alternatives:
- **Resolution-based** (resolve each reference through `@use`/`@import` scope to its exact
  declaration): precise on cross-file name collisions, but a large, fragile effort; overkill for
  a codebase with a central, uniquely-named vars file.
- **Rely on the platform**: the IDE provides no project-wide SCSS unused analysis and it can't be
  extended.

Trade-off (documented limitation): on a true name collision (same symbol name declared in two
files, one used), the unused one is conservatively treated as used (not flagged) — never a false
"unused". No transitive dead-chain analysis (a var used only to compute another, itself-dead var
counts as used).

## Components

New package `src/main/kotlin/com/webdx/scsssymbols/`.

### `ScssSymbols` (core helper object)
- `data class Decl(name, kind, element)` where `kind ∈ {VARIABLE, FUNCTION, MIXIN, PLACEHOLDER}`
  and `element` is the declaration name PSI element (inspection target / go-to target).
- `fun declarationsIn(file: PsiFile): List<Decl>` — declarations in one file, via the verified
  PSI element types above (reusing the regex kinds of `scssDefinedSymbols` to classify).
- `fun referencedNames(project): Map<Kind, Set<String>>` — cached
  (`CachedValuesManager` + `PsiModificationTracker.MODIFICATION_COUNT`) project-wide set of
  referenced names per kind, from a regex scan of all scss texts. Namespace prefix stripped.
- `fun referencesOf(name, kind, project): List<PsiElement>` — every reference site (loads PSI of
  the files that text-match the name, then locates the reference elements) — for Find Usages.
- `fun referenceAt(element): Pair<String, Kind>?` — classify a caret element as a symbol
  reference and return (bare name, kind) — for go-to.

Reference regexes (namespace `(?:[\w-]+\.)?` optional, stripped):
- variable `X`: `(?<![\w-])\$X` that is **not** the LHS of a declaration (`$X` not immediately
  followed by `\s*:` at a statement position);
- function `X`: `(?<![\w.$-])X\s*\(` excluding the `@function X(` declaration;
- mixin `X`: `@include\s+(?:[\w-]+\.)?X\b`;
- placeholder `X`: `@extend\s+(?:[\w-]+\.)?%X\b`.

To build `referencedNames` generically (without knowing declared names first): collect all
`$word` in reference context, all `@include [ns.]name`, all `name(` call tokens, all
`@extend [ns.]%name` across the project, into four sets.

### `ScssUnusedSymbolInspection` (`localInspection`, `language="CSS"` once)
For each `Decl` in the file, if `name !in referencedNames(project)[kind]`, register a
`LIKE_UNUSED_SYMBOL` problem on `decl.element` ("Unused SCSS <kind> '<name>'"). Registered once on
`language="CSS"` (covers SCSS/SASS dialects without 2–3× duplication — gotcha #9).

### `ScssSymbolFindUsagesHandlerFactory` (`findUsagesHandlerFactory`, `order="first"`)
`canFindUsages` true when the element is a symbol **declaration** name token. The handler runs in
a `ReadAction` and reports `UsageInfo` for each element from `referencesOf(name, kind, project)`.
Mirrors `CssModuleFindUsagesHandlerFactory` (cheap candidate-file scan, then PSI on matches).

### `ScssSymbolGotoDeclarationHandler` (`gotoDeclarationHandler`)
On a reference element (`referenceAt` non-null), resolve name → declaration: find the declaring
file via `scssSymbolIndex` (and, when the use is namespaced `ns.x`, prefer the file that `@use`s
under `ns` for precision when cheaply available), then return the matching `Decl.element`. The
standard go-to EP works for `.scss` (the TS-Go fork only intercepts `.tsx`, gotcha #7), so no
GotoDeclaration-action override is needed.

## plugin.xml

Add (all under `defaultExtensionNs="com.intellij"`):
- `localInspection` (language="CSS") → `ScssUnusedSymbolInspection`
- `findUsagesHandlerFactory` (order="first") → `ScssSymbolFindUsagesHandlerFactory`
- `lang.gotoDeclarationHandler` → `ScssSymbolGotoDeclarationHandler`

## Scope / non-goals (YAGNI)

In scope: `$var`, `@function`, `@mixin`, `%placeholder`; all reference forms above; project-wide
`.scss`/`.sass`. Out of scope: precise per-scope resolution on name collisions (conservative
fallback); transitive dead-chain analysis; CSS custom properties (`--var`); detecting unused
function/mixin **parameters**; rename.

## Testing (BasePlatformTestCase, real WebStorm SDK)

- `ScssSymbolsTest`: `declarationsIn` returns the 4 kinds with correct elements;
  `referencedNames` collects names across forms (bare, `ns.x`, `#{}`, `@include`, call, `@extend`);
  `referenceAt` classifies caret uses.
- Unused inspection (highlighting): an unreferenced `$var`/`@function`/`@mixin`/`%placeholder` is
  greyed; a referenced one is not — including referenced via `ns.x` / `@include` / interpolation /
  same-file value use; a symbol referenced only in another file is not greyed.
- Find Usages: lists only the real reference sites for the symbol across files.
- Go-to: from `v.$x` / `$x` / `@include name` / `func(` / `%ph` lands on the declaration.

## Docs

CHANGELOG entry + README section ("SCSS symbol usage") on implementation; bump version (1.9.0).
