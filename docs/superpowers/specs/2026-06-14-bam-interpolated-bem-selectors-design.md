# Design: "bam" — interpolated / `&`-nested BEM class selectors in CSS modules

**Date:** 2026-06-14
**Status:** Approved (design)
**Component:** `com.webdx.cssmodules`

## Problem

A common SCSS-module style in this codebase declares classes via a variable-as-selector
BEM pattern (the user calls it **"bam"**). Example — `BamExample.module.scss`:

```scss
$sidebar: '.sidebar';

#{$sidebar} {          // → .sidebar
  &--expanded {        // → .sidebar--expanded
    #{$sidebar}__search { display: block; }   // → .sidebar__search
  }
  &__search { display: none; }                // → .sidebar__search
  &__mobile-toggle { color: red; }            // → .sidebar__mobile-toggle
}
```

The real exported class names are `sidebar`, `sidebar--expanded`, `sidebar__search`,
`sidebar__mobile-toggle`, … — produced by SCSS interpolation (`#{$var}`) and `&`
BEM concatenation, which the IntelliJ CSS parser does **not** expand.

**Verified via a PSI probe:** this pattern produces **zero `CssClass` nodes**. Every
selector is a `CssSimpleSelectorImpl` wrapping a `SCSSInterpolationImpl` (`#{$sidebar}`)
and/or a literal `&`-suffix (`&__search`). Because all of the plugin's CSS-module
features key off the platform's `CssClass` type, these classes are completely invisible:
no `styles.` completion, flagged as "unknown class" on the JS side, no go-to, no
Find Usages, never greyed when unused.

PSI shapes observed (relevant nodes):
- `$sidebar: '.sidebar'` → `SassScssVariableDeclarationImpl` containing
  `SassScssVariableImpl` (`$sidebar`) + `CssTermListImpl → CssTermImpl → CssStringImpl`
  (`'.sidebar'`).
- Block selector → `CssSelectorImpl → CssSimpleSelectorImpl → SCSSInterpolationImpl`
  (`#{` prefix, `CssTermList` with the `$sidebar` variable, `}` suffix).
- `&--expanded`, `&__search`, `#{$sidebar}__search` → each a `CssSimpleSelectorImpl`
  whose `.text` is the raw selector; the `&`/suffix is not split into a `CssClass`.

## Goal

Make the bam-generated class names first-class in all four existing CSS-module
behaviors the user selected:

1. `styles.` member completion
2. "Unknown CSS class" inspection (JS/TS side)
3. Go-to-declaration on `styles.<bamClass>`
4. "Unused CSS class" inspection (CSS side) + scoped Find Usages

**Bonus (falls out of the same resolver):** plain nested BEM with a *literal* parent
(`.foo { &__bar {} }`, no variable) is also invisible today (`&__bar` has no `CssClass`).
Resolving `&` against a literal-class parent fixes that case too, at no extra cost.

## Approach

A small PSI-based SCSS **selector resolver** (`BamSelectors`) that:
- reads top-level string `$var` values,
- walks the ruleset nesting resolving `&` (→ parent's resolved selector) and `#{$var}`
  (→ the variable's string value),
- extracts class-name tokens, and pairs each with the `CssSimpleSelector` element that
  declares it.

Rejected alternatives:
- **Text-only regex:** yields names but no PSI element, so go-to / Find Usages / unused
  targeting are impossible — fails the selected scope.
- **Full Sass evaluation:** needs a Sass interpreter/parser; overkill and fragile, no
  library available.

## Components

### New: `BamSelectors` (`src/main/kotlin/com/webdx/cssmodules/BamSelectors.kt`)

An `internal object`, cached per file via `CachedValuesManager.getCachedValue` +
`PsiModificationTracker.MODIFICATION_COUNT` (same pattern as `CssModules.collectClassOrigins`).

Public API:
- `fun bamClassDeclarations(moduleFile: PsiFile): Map<String, PsiElement>`
  — subject class name → the `CssSimpleSelector` element that declares it (first
  occurrence wins on duplicates). The map's `.keys` are the bam class names; the values
  are the go-to / unused / Find-Usages targets.
- `fun bamClassForElement(element: PsiElement): String?`
  — for a caret element inside a bam selector, return the subject class name of the
  enclosing `CssSimpleSelector` (used by Find Usages `canFindUsages`/`processScoped`).
  Returns null when the element is not a bam selector.

### Resolution algorithm

1. **Variable table.** Collect top-level `SassScssVariableDeclaration`s in the file →
   `Map<varName, stringValue>`. The value is the content of the declaration's
   `CssString` with surrounding quotes stripped. The value may already contain a leading
   `.` (`'.sidebar'`) or not (`'sidebar'`) — both are handled because class extraction
   runs on the *final resolved selector text* (step 4), not on the raw value.
   Non-string-valued variables are ignored.

2. **Own resolved selector, per ruleset.** For each `CssRuleset`, compute the resolved
   text of its own `CssSelector`(s):
   - substitute each `#{$var}` interpolation with the variable's value (skip the selector
     if the variable is unknown / non-string);
   - if the raw selector text contains `&`, replace each `&` with the parent ruleset's
     own resolved selector text (recursive walk up the ruleset nesting);
   - a nested selector with no `&` is an absolute/descendant selector — its own compound
     is the selector itself (e.g. `#{$sidebar}__search` → `.sidebar__search`).
   The root parent may be either an interpolated selector (`#{$var}`) **or** a literal
   class (`.foo`), which is why literal-parent BEM nesting is covered.

3. **Cycle / depth safety.** The parent walk is bounded by the PSI nesting depth (finite);
   guard against a missing/unresolved parent by bailing to "unresolved" for that selector.

4. **Class extraction.** From the resolved own-compound string, extract class tokens with
   `\.([A-Za-z0-9_-]+)`. The **subject** class (the declared name for this ruleset) is the
   class token of the own compound; pseudo-elements/classes and combinator context
   (`&:after`, `&::-webkit-scrollbar`, descendant prefixes) contribute no subject class and
   are silently skipped. Map `subjectClass → this CssSimpleSelector`.

5. **Result.** Union of all subject classes → the declarations map. Caching keyed on the
   file + PSI modification count.

### Integration points (existing features)

- **`CssModules.collectClassNames(moduleFile)`** — union the existing `CssClass` names with
  `BamSelectors.bamClassDeclarations(moduleFile).keys`. This single change feeds:
  - `styles.` completion (`CssModuleStylesCompletion`, via `collectClassOrigins` /
    `collectAllClassNames`),
  - the "unknown class" inspection (`CssModuleUnknownClassInspection`),
  - `@import`-chain inlining (bam classes in a shared module flow to consumers).
  Bam classes declared in the file map to that file in `collectClassOrigins` (own file),
  which is correct.

- **`CssModuleClassNavigation.resolveTarget`** — after `collectClassOrigins` yields the
  declaring file, the current code looks up a `CssClass` by name and bails if none. Add a
  fallback: when no `CssClass` matches, return
  `BamSelectors.bamClassDeclarations(declaringFile)[name]` (the `CssSimpleSelector`).
  Go-to then lands on the `&__search` / `#{$sidebar}` selector.

- **`CssModuleUnusedClassInspection`** — in addition to visiting `CssClass` elements,
  register a `LIKE_UNUSED_SYMBOL` problem on each bam declaration element whose subject
  name is not in `collectUsedClassNames(file)`. Implementation: in `buildVisitor`, build
  the `bamClassDeclarations(file)` map once and, in `visitElement`, when the element is one
  of the map's declaration elements and its name ∉ `used`, flag it. (The existing
  `used == null` short-circuit still applies — no JS consumer means nothing is flagged.)

- **`CssModuleFindUsagesHandlerFactory`** — `canFindUsages` currently requires
  `resolveCssClass(element) != null`. Add a bam branch: accept when the element is inside a
  bam selector (i.e. `BamSelectors.bamClassForElement(element) != null`). In
  `processScoped`, derive the class name from `bamClassForElement` when there is no
  `CssClass`. The downstream importer scan (`<binding>.<className>`) is unchanged because
  consumers reference `styles.sidebar__search` exactly.

No `plugin.xml` changes: all four features are already registered; this only widens what
they recognize.

## Scope / non-goals (YAGNI)

In scope (covers `BamExample.module.scss` fully):
- top-level string `$var` defined in the **same** module file;
- `#{$var}` interpolation in selectors;
- `&` BEM concatenation (`&__el`, `&--mod`) with interpolated **or** literal-class parents;
- arbitrary nesting depth.

Out of scope (documented limitations; resolver leaves these selectors invisible, never
wrong):
- `$var` imported via `@use` / `@import` / `@forward` from another file;
- `@each` / `@for` loops, Sass maps, function-computed selector fragments;
- non-string variable values.

These are safe by construction: an unresolved selector is simply omitted from the
declarations map — it never produces a wrong class name or a false "unused" flag.

## Testing

Following existing test patterns (`BasePlatformTestCase`, real WebStorm SDK, no mocks):

- **`BamSelectorsTest` (PSI/logic):** `bamClassDeclarations` returns the expected subject
  names for the `BamExample` shape (`sidebar`, `sidebar--expanded`, `sidebar__search`,
  `sidebar__mobile-toggle`, `sidebar__content` from a nested `#{$var}__content`); each maps
  to a `CssSimpleSelector` whose text is the raw selector; literal-parent BEM
  (`.foo { &__bar {} }`) yields `foo__bar`; pseudo selectors yield nothing; an unknown
  variable yields nothing (no crash).
- **`CssModuleInspectionTest` additions:** a `styles.sidebar__search` is **not** flagged
  unknown; an unused bam class is greyed; a used one is not.
- **`CssModuleCompletionTest` addition:** `styles.` completion offers bam names.
- **Navigation test:** go-to on `styles.sidebar__search` resolves to the `&__search`
  selector element.
- **Find Usages test:** Find Usages on a bam selector lists only the `styles.<bamClass>`
  references in importing files.

## Docs

On implementation: add a CHANGELOG entry (next minor, e.g. `1.8.0`) and a README bullet
under the CSS Modules section describing interpolated/`&`-nested BEM class support and the
documented limitations.
