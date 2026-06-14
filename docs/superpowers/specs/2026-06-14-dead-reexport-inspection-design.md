# Dead re-export inspection — design

**Date:** 2026-06-14
**Status:** Approved design, pending spec review

## Problem

A "barrel" file re-exports symbols from neighbours but no real consumer ever imports
through it — the actual consumer reaches the underlying file by a different path (e.g. a
deep `require('@/.../Screen/Screen')` that bypasses the barrel). WebStorm shows everything
as "used" because each `export ... from` link looks like a usage, so a transitively-dead
barrel is invisible.

Concrete case (`intch_application`):

```ts
// src/screens/ExpertsHubScreens/ExpertsHubInitialScreen/index.ts
export { ExpertsHubInitialScreen } from './ExpertsHubInitialScreen'
export { default } from './ExpertsHubInitialScreen'
```

- The navigator does `require('@/screens/ExpertsHubScreens/ExpertsHubInitialScreen/ExpertsHubInitialScreen')`
  — the `.tsx` directly, **bypassing** this `index.ts`.
- The only importer of `index.ts` is the parent barrel `ExpertsHubScreens/index.ts`
  (`export * from './ExpertsHubInitialScreen'`), which itself has **no** real consumer.

So `index.ts` is transitively dead, but no existing tooling flags it.

## Goal

A WebStorm inspection that flags an ES6 re-export (`export ... from`) when the re-exported
name is **transitively unconsumed** — no real (non-re-export) importer reaches it even
through chains of `export ... from`.

## Decisions (from brainstorming)

| Topic | Decision |
|---|---|
| Semantics | **Transitive** reachability through re-export chains (not just direct importers). |
| Granularity | **Per-line / per-name**: flag each `export ... from` per re-exported name. |
| "Live" root | **Any non-re-export reference** keeps it alive (import / `require` / dynamic `import`), even if the imported name is then unused locally. |
| Uncertainty | **Conservative**: stay silent unless 100% sure there is no consumer. |
| Quick-fix | **None (MVP)** — highlight only. |
| Vehicle | `LocalInspectionTool`, per JS/TS language, matching `RnStyleUnusedKeyInspection`. |

## Unit of analysis

Each ES6 export declaration that has a `from` clause, considered **per re-exported name**:

- `export { X } from './x'` — name `X`
- `export { X as Y } from './x'` — exported name `Y`
- `export { default } from './x'` — name `default`
- `export * from './x'` — the whole namespace (all names)

Not restricted to "pure barrel" files: a re-export inside a mixed file is checked too.
The anchor for the warning is the exported-name identifier (or the `*` / `from` clause for
star re-exports).

## Algorithm — reverse reachability over the re-export graph

For a re-exported name `X` in file `F`, decide `isLive(F, X)`:

1. **Find references to module `F`** via index-backed `ReferencesSearch` on the `PsiFile`.
   String-literal module paths in `import` / `require` / `export ... from` resolve to the
   file through the IDE's own resolution, so `@/` path aliases and `require()` are handled
   automatically (we do not re-implement tsconfig/babel alias resolution).
2. For each reference site:
   - **Unresolvable / dynamic** module reference anywhere that *could* be `F`
     (`require(variable)`, `import(variable)`, unresolved path) → treat as a possible
     consumer → `isLive = true` (conservative), stop.
   - **Non-re-export** consumer that pulls `X` (or all of `F`): `import { X }`,
     `import X` (default), `import * as ns`, `require(F)`, `require(F).X`, `import(F)`
     → `isLive = true`, stop.
   - **Re-export** `export { X (as Y) } from F` or `export * from F` in file `G`
     → recurse: `isLive(G, Y)` (or `isLive(G, X)` for `*`). If any recursion is live,
     `isLive = true`, stop.
3. If no reference yields a live path → `isLive = false` → register a weak warning
   (`ProblemHighlightType.LIKE_UNUSED_SYMBOL`) on `X`'s anchor in `F`.

Cycle protection: a `visited` set of `(file, name)` pairs; a re-visited node contributes no
new liveness.

### Conservative widening rules (avoid false positives)

- `import * as ns from F`, `require(F)` (whole module), and `export * from F` are treated as
  consuming **all** of `F`'s exports — we cannot reliably narrow to a single name, so they
  keep every name of `F` live.
- Any module reference we cannot resolve to a concrete file (dynamic specifier, unresolved
  path) is treated as a potential consumer of whatever it might point at → silence.
- A name with **zero** resolvable references at all (not even a re-export) is still only
  flagged when we are certain the search was complete; if reference search is unavailable
  (e.g. dumb mode), the inspection yields nothing.

## Scope

- Search scope: `GlobalSearchScope.projectScope(project)`. Exclude `node_modules` and
  `.d.ts` declaration files.
- Test files count as real consumers.
- Languages registered: `TypeScript`, `TypeScript JSX`, `JavaScript`, `ECMAScript 6`,
  `JSX Harmony` (same set as existing inspections).

## Performance

- Cheap pre-check: the visitor reacts only to ES6 export declarations that have a `from`
  clause. All other files / elements are skipped immediately.
- Reachability uses index-backed `ReferencesSearch` (narrowed by the word index), **not** a
  brute-force scan of every project file like the current `RnStyles.importersForExport`.
- Results cached via `CachedValuesManager` keyed on PSI modification count, so repeated
  on-the-fly passes are cheap until the relevant PSI changes.
- `isLive` recursion is bounded by the `visited` set and by the (typically small) re-export
  fan-out.

## Components

- `com.webdx.deadexports.DeadReExports` — pure logic: enumerate re-exported names of a
  declaration, resolve module references, and compute `isLive` with caching. No platform
  UI concerns; unit-testable.
- `com.webdx.deadexports.DeadReExportInspection : LocalInspectionTool` — thin visitor that
  finds `from`-bearing export declarations, calls `DeadReExports.isLive`, and registers the
  weak warning. Mirrors `RnStyleUnusedKeyInspection`.
- `plugin.xml` — five `<localInspection>` registrations (one per language above).

## Testing (`BasePlatformTestCase`, in-memory fixtures)

1. **Dead barrel, bypassed by deep require** → the re-export is flagged.
2. **Barrel with a real ES import** of the name → not flagged.
3. **Partially dead barrel** — one name consumed, another not → only the dead name flagged.
4. **Re-export chain with a live root** (consumer imports from the top barrel) → no flag
   anywhere in the chain.
5. **`import * as` / `require(F)` whole-module** consumer → silences all names.
6. **Dynamic `require(variable)`** present → conservative silence.
7. **Cycle** (A re-exports B, B re-exports A, no real consumer) → both flagged, no infinite
   loop.

## Out of scope (YAGNI)

- Quick-fix / Safe Delete (deferred; may be added later).
- A standalone "audit whole project" action/report.
- Detecting imported-but-locally-unused symbols (that is local dead-code analysis, a
  different and far more false-positive-prone feature).
- Configurable glob exclusions for entry points.
