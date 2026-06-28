# Dead export declaration inspection — design

## Problem

A directly-declared export — `export const SomeFun`, `export function Some`, `export default …`,
`export interface Foo` — can be exported yet reached by **no real consumer** anywhere in the
project. The motivating case:

```tsx
// components/SomeFun.tsx
export const SomeFun: React.FC = () => <div></div>
SomeFun.displayName = 'SomeFun'
export function Some() {}
```

The only reference to either symbol is `export * from './SomeFun'` in `components/index.ts`, and
that barrel link is itself dead (no consumer reaches through it). WebStorm's built-in unused-symbol
inspection treats the barrel re-export as a "use", so it never flags these. We want to flag them.

The existing `DeadReExportInspection` already greys dead re-export *links* (`export … from`). This
adds the sibling case: directly-declared exports with no external consumer.

## Approach

A new sibling inspection **`DeadExportInspection`** in `com.webdx.deadexports`, sharing
`DeadReExports.Analyzer`. The split:

- `DeadReExportInspection` — owns `export … from` re-export links (unchanged).
- `DeadExportInspection` — owns directly-declared exports (no `from` clause).

Separate inspections keep each focused, each independently toggleable, and anchor warnings on the
right element (declaration name vs re-export specifier).

## Liveness rule

Two reachability questions on `analyzer`, both reverse reachability over the import / re-export
graph (the same machinery that powers re-export flagging):

- `isExternallyLive(F, N)` — some real (non-re-export) consumer reaches `N` through the import /
  re-export graph (directly or transitively through barrels). Searches references to the *module
  file*, so same-file references never count. Answers **"is the `export` keyword needed?"**
- `isLive(F, N)` — `isExternallyLive`, OR `N`'s declared symbol is referenced *within `F`* by
  another export `M` that is itself live (recursively, excluding self-references). Answers **"is the
  symbol used at all?"** The same-file symbol is found by scanning `F` (not cross-module resolution,
  which follows `… from` chains and overflows the IDE resolver on cyclic barrels).

Three outcomes for a declared export `N`:

| `isExternallyLive` | `isLive` | Verdict |
|---|---|---|
| true | — | needed externally → nothing |
| false | true | alive but only used in-file → **redundant-export warning** (make it local) |
| false | false | unreached anywhere → greyed unused |

Consequences:

- `SomeFun` / `Some` (only ref is a dead barrel `export *`; `SomeFun.displayName` is a self-ref) →
  not live → greyed unused. ✓
- `Inner` referenced only by an externally-consumed `Outer` (`Outer.inner: Inner`) → alive, export
  redundant → **warning**, not greyed. ✓ (was previously greyed — the motivating fix.)
- `helper` referenced only by a *dead* sibling → both stay greyed unused. ✓

## What the visitor flags

Only in module files, only when the symbol **is exported** (`ES6ImportHandler.isExported`) and is
**not** a `… from` re-export (those belong to `DeadReExportInspection`):

- **Value exports** — `export const/let/var`, `export function`, `export class`, and local
  `export { foo }` (specifiers with no `from`). Each exported name is queried.
- **Default exports** — `export default function/class/const/<expr>` → query `"default"`.
  Next.js page/app entry points are skipped via `NextEntryPoints`, so page defaults are not flagged.
- **TS types** — `export interface`, `export type`, `export enum` → query the type name.

`export const A = 1, B = 2` is queried per binding, each anchored on its own name identifier.

- **Anchor:** the exported symbol's name identifier (fall back to the declaration element).
- **Messages / highlight** (per the table above):
  - unreached → `"Export '<name>' is never used (no consumer reaches it)"`,
    `ProblemHighlightType.LIKE_UNUSED_SYMBOL`.
  - used only in-file → `"Export '<name>' is only used in this file; 'export' is redundant (can be
    made local)"`, `ProblemHighlightType.WARNING`.

## Enumerating exported names

For each candidate top-level element in the file, resolve `(name, anchor)` pairs:

- `ES6ExportDefaultAssignment` → name `"default"`, anchor its `getNamedElement()` (or the keyword).
- `ES6ExportDeclaration` **without** a from-clause and **not** `isExportAll`:
  - export specifiers (`export { a, b as c }`) → exported name per specifier (`declaredName`),
    anchored on the specifier's exported-name identifier (same anchor logic as the re-export
    inspection).
  - a wrapped declaration (`export const/function/class/interface/...`) → the declared
    `JSPsiNamedElement`(s) and their name identifiers.
- Bare exported declarations not wrapped in `ES6ExportDeclaration` (SDK shape permitting) detected
  via `ES6ImportHandler.isExported` on the named element.

Default `default`-name binding uses the import side's `"default"` convention already encoded in
`consumedNames` (a default `import X` contributes `"default"`).

## Reused conservatism (no false positives)

Inherited from `DeadReExports`:

- Unresolvable / dynamic module references → consumer assumed → live.
- Reference search unavailable (dumb mode) → inspection yields nothing.
- Next.js entry-point files are live and are skipped wholesale in `buildVisitor`.
- `node_modules` / `.d.ts` excluded by the project search scope.

## Scope

- Languages: same set as the existing inspections (`TypeScript`, `TypeScript JSX`, `JavaScript`,
  `ECMAScript 6`, `JSX Harmony`).
- Search scope: `GlobalSearchScope.projectScope`.
- Test files count as real consumers.

## Testing

Inspection-level (`DeadExportInspectionTest`):

- Unused `export const` / `export function` reached only by a dead barrel `export *` → flagged.
- Same export kept live by a real named import → not flagged.
- Unused `export default` → flagged; a Next.js page default (`next.config` present) → not flagged.
- Unused `export interface` / `export type` → flagged; used type → not flagged.
- A symbol self-referenced only in its own file (`X.displayName = …`) → greyed unused.
- A symbol used in-file by another *live* export (`Outer.inner: Inner`) → redundant-export warning,
  not greyed; the externally-consumed carrier is not reported.
- A symbol used in-file only by a *dead* sibling → both greyed unused.
- `export { x } from './y'` is left to `DeadReExportInspection` — `DeadExportInspection` does not
  also flag it.

Mutation-check the new liveness path (force always-live / always-dead) to confirm the new tests
have teeth, as done for the re-export work.

## Out of scope

- A quick-fix to delete the symbol (unused case) or strip the `export` keyword (redundant case)
  (future).
