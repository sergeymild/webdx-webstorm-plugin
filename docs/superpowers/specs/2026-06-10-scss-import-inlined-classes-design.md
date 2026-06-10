# SCSS `@import`-inlined classes in CSS-module DX

## Problem

Sass inlines an imported `.module.scss` into the compiled output of the importing
module. So when `StepGraph.module.scss` does

```scss
@import "@/src/containers/Onboarding/common.module.scss";
```

every class from `common.module.scss` (e.g. `.nextButton`) lands in the **local
scope** of `StepGraph.module.scss` and is exported on its `styles` object.
`styles.nextButton` is therefore a valid hashed class, not `undefined`.

The plugin currently reads class names only from the module file itself
(`CssModules.collectClassNames`) and never expands `@import`. Consequences in
`StepGraph.tsx` (which imports `StepGraph.module.scss` as `styles`):

1. **Unknown-class inspection** flags `styles.nextButton` red — it is not among
   `StepGraph.module.scss`'s own classes.
2. **`styles.` completion** does not offer the imported classes.
3. **Unused-class inspection** greys `.nextButton` in `common.module.scss` —
   `findImporters` searches for JS files importing that file, but `common` is
   imported by *another scss file* via `@import`, not by JS, and
   `importBindingName` does not recognise the scss `@import`.

Go-to-definition on `styles.nextButton` already works (via the IDE / TS fork) and
is **out of scope**.

## Scope

In scope, all resolved from source files on disk (independent of the TS service,
consistent with the rest of the plugin):

- Follow `@import`, `@use`, and `@forward` in `.module.scss|css|sass|less` files.
- Resolve both path forms: **relative** (`./`, `../`, bare) and the **`@/` alias**
  from `tsconfig.json` (`compilerOptions.paths` + optional `baseUrl`).
- Follow import chains **transitively**, with cycle protection.
- Only `.module.*` import targets contribute classes; non-module imports
  (`vars.scss`) are ignored (they hold variables/mixins, no exported classes).
  This is a deliberate limitation — a non-module stylesheet whose rules would be
  inlined and localized is not handled.

Out of scope: go-to-definition/rename for `styles.foo` ↔ CSS class (already works
/ a separate "next step").

## Architecture

All new logic lives in `CssModuleShared.kt` (`CssModules` object). The three
consumers change minimally.

### Import-resolution layer (new, in `CssModules`)

- `parseScssImports(scssFile: PsiFile): List<String>`
  Regex over the scss text, extracting the path(s) from `@import`, `@use`, and
  `@forward`. Handles both quote styles and a comma-separated list in a single
  `@import "a", "b";`.

- `resolveImportPath(fromDir: VirtualFile, project: Project, path: String): VirtualFile?`
  - relative (`./`, `../`, bare) → existing `resolveRelative`;
  - `@/...` alias → resolved via the nearest `tsconfig.json` found by walking up
    from the file (`compilerOptions.paths` matched by prefix, relative to
    `baseUrl` or the tsconfig dir). For the project: `"@/*": ["./*"]`, baseUrl
    unset → tsconfig dir (web root).
  - Tries the path as-is and with the scss extension appended.

### Transitive class collection (new)

- `collectAllClassNames(moduleFile: PsiFile): List<String>`
  = own classes (`collectClassNames`) ∪ classes of every transitively imported
  `.module.*` file. Cycle-guarded by a `visited` set keyed on `VirtualFile`.
  Cached on `moduleFile` via `CachedValuesManager`.

### Module import graph (new, for the unused inspection)

- `moduleImportGraph(project): Map<VirtualFile, Set<VirtualFile>>`
  For every `.module.scss|css|sass|less` file (enumerated via
  `FilenameIndex.getAllFilesByExt`), its set of direct `.module.*` import targets.
  Cached on `project` + `PsiModificationTracker.MODIFICATION_COUNT`.

- `modulesTransitivelyImporting(moduleFile): Set<VirtualFile>`
  Reverse BFS over the graph: all modules `N` that transitively import
  `moduleFile`, plus `moduleFile` itself.

## Consumer changes

1. **`CssModuleStylesCompletion.fillMemberCompletion`** — `collectClassNames` →
   `collectAllClassNames`. Imported classes now appear after `styles.`.

2. **`CssModules.cssModuleBindings`** (used by `CssModuleUnknownClassInspection`)
   — `collectClassNames` → `collectAllClassNames`. `styles.nextButton` no longer
   flagged.

3. **`CssModuleUnusedClassInspection` / `CssModules.collectUsedClassNames`** —
   widen the "used" computation: for the module `M` under inspection, gather the
   JS importers of **every** module in `modulesTransitivelyImporting(M)` (reusing
   `findImporters` — JS imports are direct and resolve), collect their
   `<binding>.<member>` references, and count a member as used iff it is in `M`'s
   **own** class set. `M`'s own classes not in that set are still flagged, so real
   dead code in a shared file is still caught.
   - Guard: if `M` has neither JS importers nor any scss module importing it, keep
     the current "no importer → don't cry wolf" behaviour (return early).

## Data flow (the reported case)

`StepGraph.tsx` → `import styles from './StepGraph.module.scss'`.
`cssModuleBindings(StepGraph.tsx)` → `collectAllClassNames(StepGraph.module.scss)`
follows `@import "@/.../common.module.scss"` → includes `nextButton`. Unknown
inspection: `nextButton ∈ classes` → no warning. Completion: `nextButton` offered.

`common.module.scss` unused inspection: `modulesTransitivelyImporting(common)` =
`{common, StepGraph.module.scss, …}`. `findImporters(StepGraph.module.scss)` →
`StepGraph.tsx` with binding `styles`. `styles.nextButton` referenced, `nextButton`
∈ common's own classes → used → not greyed.

## Error handling

- Missing/unparseable `tsconfig.json` → alias paths simply don't resolve (return
  null); relative imports still work. No exceptions surface.
- Cycles in `@import` → `visited` guard; BFS terminates.
- `PsiManager.findFile` null / non-module target → skipped.
- All PSI access stays inside read-action contexts as today.

## Testing (TDD — tests written first)

Unit (pure / PSI helpers):

- `parseScssImports`: `@import`/`@use`/`@forward`, both quote styles,
  comma list, trailing options on `@use`/`@forward`, no-match.
- `resolveImportPath`: relative up/down/sibling; `@/` alias via a fixture
  `tsconfig.json` with `"@/*": ["./*"]`; unresolvable → null; extension appended.
- `collectAllClassNames`: own ∪ one import; transitive (A→B→C); cycle (A→B→A);
  non-module import ignored.
- `moduleImportGraph` / `modulesTransitivelyImporting`: direct, transitive,
  cycle, unrelated module excluded.

Integration (light `BasePlatformTestCase` fixtures):

- Completion after `styles.` offers an imported class.
- Unknown-class inspection is silent on a `styles.<importedClass>`.
- Unused-class inspection does **not** grey an imported class that is used via a
  consuming module, but **does** grey one that is never referenced.

Run: `JAVA_HOME=… ./gradlew test`.

## Out of scope / future

- Non-module stylesheet rules inlined into a module.
- Go-to-definition / rename for `styles.foo` ↔ CSS class.
- `@/` alias resolution for JS `import` of css modules (only scss `@import`
  aliases are handled here; JS css-module imports in this project are relative).
