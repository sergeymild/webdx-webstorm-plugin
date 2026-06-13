# React Native `StyleSheet.create` support — design

Date: 2026-06-13
Status: approved (ready for implementation plan)

## Goal

Bring the same source-resolved developer-experience features the plugin already
provides for CSS Modules to **React Native `StyleSheet.create` styles**: go-to the
effective declaration, scoped Find Usages, an "unknown style key" inspection, and an
"unused style key" inspection. Everything resolves from the actual `.ts`/`.tsx`
source (generic PSI, no TypeScript language service), so it works regardless of
whether the TS service types the object — including under the TS-Go fork.

## Motivation (observed in `react-native-musescore`)

The TS service does **not** give correct behaviour for `StyleSheet.create` styles here:

- An unused key is **not** flagged. Example: `boxRadius` in
  `src/modules/search/elements/Radio/styles.ts` is referenced nowhere, yet nothing
  greys it out.
- Find Usages / go-to on a style property resolves like an untyped member: clicking
  the `title` property in `src/App/Courses/Components/about/styles.tsx` lists every
  `title` in the project, even from unrelated files — the same "same-named member
  collision" problem CSS Modules had.

So `styles.<key>` must be resolved from source, exactly as `styles.<class>` is for
CSS modules.

## Codebase survey (drives the scope)

In `react-native-musescore/src`:

- **529** files use `StyleSheet.create`. The only `*.create(` wrapper is
  `StyleSheet.create` (plus unrelated `LayoutAnimation.create`).
- **497** are inline `const styles = StyleSheet.create({...})` in the component file;
  **32** are `export const styles = StyleSheet.create({...})` in a separate
  `styles.ts(x)` consumed via `import { styles } from './styles'`.
- Binding name is almost always `styles` (525), occasionally `expStyles` (3),
  `globalStyles` (1). **Detection must key off `= StyleSheet.create(...)`, not the
  variable name.**
- **0** `export default StyleSheet.create(...)`.
- **0** spread/composition (`...base`) inside a `StyleSheet.create` object.
- **7** destructuring sites `const { x } = styles` (e.g. `About.tsx`:
  `const { title } = styles` then `<Text style={title}>`).

Consequences: no default-export support needed, **no `@import`-style composition
chain** (this is strictly simpler than the CSS-module case), but **destructuring must
be a first-class supported shape** for navigation, find-usages, and unused-detection.

## Scope

In scope — full parity with CSS Modules, minus completion:

1. **Go-to** on `styles.<key>` → the property in the `StyleSheet.create` object.
2. **Scoped Find Usages** on a style property.
3. **"Unknown style key"** inspection (JS/TS side).
4. **"Unused style key"** inspection (JS/TS side).

Destructuring (`const { title } = styles`) is **fully supported** across all four:
navigation and find-usages work on the destructured local itself, and a destructured
binding counts as a use for the unused inspection.

Out of scope (YAGNI — justified by the survey):

- **No `styles.` completion** — the object is a real TS object; the gap is navigation
  and inspections, not completion.
- **No spread/composition chains** — none exist in the codebase (the CSS `@import`
  inlining machinery has no analog here).
- **No `export default` styles** — none exist.
- **Computed keys** `styles['foo']` / `['bar']: …` — not flagged either way (treated as
  opaque; never reported unknown or unused).

## Architecture

New package `src/main/kotlin/com/webdx/rnstyles/`, parallel to `com/webdx/cssmodules`,
with a shared helpers object `RnStyles` (the analog of `CssModules`). All logic runs on
generic PSI — no TS service calls.

### Core abstraction: a "StyleSheet object"

A StyleSheet object is a variable initializer `StyleSheet.create({ <object literal> })`.
In PSI: a `JSCallExpression` whose method expression is a reference with qualifier text
`StyleSheet` and referenced name `create`, whose first argument is a
`JSObjectLiteralExpression`. The object literal's top-level properties are the style
"keys". Detection is textual on the `StyleSheet` qualifier (covers all 529 files; does
not depend on resolving the `react-native` import).

### `RnStyles` helpers

- `findStyleSheets(jsFile)` → for every `… = StyleSheet.create({…})` in the file
  (exported or local), the binding name → its `JSObjectLiteralExpression`.
- `styleKeys(objectLiteral)` → the set of property names (identifier and string-literal
  keys; computed and spread elements skipped).
- `keyProperty(objectLiteral, name)` → the `JSProperty` PSI element declaring a key
  (navigation target).
- `resolveStyleKeyAt(element)` → the heart of navigation/inspection. Given a PSI element
  at the caret, resolve it to `(objectLiteral, keyName)` when it is one of:
  - a `<binding>.<key>` member access where `<binding>` resolves to a StyleSheet object;
  - a reference to a local destructured from a StyleSheet object
    (`const { key } = <binding>` → uses of `key`);
  - the destructuring element itself (`const { key } = <binding>`).
  Resolution of `<binding>` checks **local first** (a same-file
  `const <binding> = StyleSheet.create(...)`), then a **named import**
  (`import { <binding> } from './x'`, alias-aware) → the exported
  `export const <binding> = StyleSheet.create(...)` in the target file. Runs against
  `containingFile.originalFile` (navigation hands a non-physical copy whose
  `virtualFile` is null — CSS gotcha #8).
- `findImporters(stylesFile, exportName)` → files importing that named export + the local
  binding each uses (alias-aware). For an inline (non-exported) StyleSheet object the
  "scope" is just its own containing file.
- `collectUsedKeys(objectLiteral)` → key names referenced across the scope as
  `<binding>.<key>` **or** via destructuring `const { key } = <binding>` (and uses of the
  resulting local). Used by the unused inspection.

### Features

**F1 — Go-to on `styles.<key>` (and destructured local) → the key property.**
A single target (one object; no override/cascade concept). The IntelliJ platform allows
only **one** overriding `GotoDeclaration` action, and the CSS feature already owns it
(`CssModuleGotoDeclarationAction`). So RN resolution is added **inside that existing
action**: try the CSS resolver first, then `RnStyles.resolveStyleKeyAt`; navigate to
`keyProperty(...)` and stop, else delegate to `super`. Wrapped in try/catch so a failure
can never break normal navigation. No new overriding action is registered.

**F2 — Scoped Find Usages on a style property.**
A `findUsagesHandlerFactory` (`order="first"`) that triggers when the target is a
`JSProperty` inside a `StyleSheet.create` object (or a destructured local resolving to
one). It lists only the `<binding>.<key>` references — plus destructuring sites
`const { key } = <binding>` and uses of the destructured local — within the scope (the
containing file for an inline object; the importer files for an exported one). PSI access
is wrapped in `ReadAction.compute { … }` (CSS gotcha #3). This fixes "clicking `title`
lists every `title` in the project".

**F3 — "Unknown style key" inspection (JS/TS).**
A `localInspection` (JS langs) that flags `styles.<key>` as a warning when `<binding>`
resolves to a StyleSheet object and `<key>` is not among its keys. Fires **only** when the
binding resolves to a StyleSheet object, so unrelated member access is never redlined.
Computed access is ignored.

**F4 — "Unused style key" inspection (JS/TS).**
A `localInspection` that greys a key declared in a `StyleSheet.create` object that is
never referenced (`<binding>.<key>` or destructured) within its scope — the containing
file for an inline object, the importer files for an exported one. This fixes `boxRadius`.

### `plugin.xml` registration

| Feature | EP | Notes |
|---|---|---|
| Go-to | (none new) | RN branch folded into existing `GotoDeclaration` action override |
| Find Usages | `findUsagesHandlerFactory` (`order="first"`) | `com.intellij` |
| Unknown / Unused inspections | `localInspection` per JS language | same language set as the CSS inspections: JavaScript, TypeScript, TypeScript JSX, ECMAScript 6 / JSX Harmony |

Reuse the established gotchas: per-language JS registration incl. **`TypeScript JSX`**
(gotcha #2), `defaultExtensionNs` not `…PointName` (gotcha #1), read-action wrapping
(gotcha #3), resolve via `originalFile` (gotcha #8).

## Testing

Tests are required and mirror the CSS-module suite: real IntelliJ engine on
`BasePlatformTestCase` fixtures, no mocks, run via
`./gradlew test`. Every shape — **inline**, **exported+imported**, and
**destructuring** — must be covered. New test classes under the `rnstyles` package:

| Test class | Target |
|---|---|
| `RnStylesLogicTest` | pure predicates: detect `StyleSheet.create` call shape, extract key names (identifier vs string key; skip computed/spread) |
| `RnStylesPsiTest` | `RnStyles` helpers: `findStyleSheets`, `styleKeys`, `keyProperty`, `resolveStyleKeyAt` (member access / destructured local / destructuring element), `resolveStyleSheetForBinding` (local + named import + aliased import), `findImporters`, `collectUsedKeys` (incl. destructuring) |
| `RnStyleUnknownKeyInspectionTest` | unknown-key warning via real highlighting; quiet on a real key; quiet on unrelated member access; quiet on computed access — inline **and** imported |
| `RnStyleUnusedKeyInspectionTest` | unused key greyed (`boxRadius` shape); a key used via `styles.x` not flagged; a key used **only** via destructuring not flagged — inline **and** exported (used through an importer) |
| `RnStyleFindUsagesTest` | scoped Find Usages on a key property → only `<binding>.<key>` refs (+ destructuring sites/uses); does not match same-named members in unrelated files; inline scope = same file, exported scope = importers |
| `RnStyleGotoDeclarationTest` | `resolveStyleKeyAt` → the single key `JSProperty`, from `styles.<key>`, from a destructured local, and from the destructuring element; local + imported + aliased binding |

Note (mirrors the CSS "partial gap"): the light fixture does not run the TS service, so
the highlighting-based inspection tests assert on the plugin's own annotations directly,
and any TS-service-driven quick-fix path (not used here) would be verified manually.

## Out-of-scope / possible next steps

- `styles.` completion (intentionally omitted; the object is typed when the TS service
  works).
- Spread/composition (`...base`) inside `StyleSheet.create` — add an inlining pass akin
  to the CSS `@import` chain if it ever appears.
- `export default StyleSheet.create(...)` resolution.
- Bracket access `styles['kebab']` for the inspections.
- Rename `styles.foo` ↔ the key property.
