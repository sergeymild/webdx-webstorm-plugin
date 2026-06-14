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

A directly-declared export named `N` in module file `F` is **live** iff `analyzer.isLive(F, N)` —
the same reverse reachability that powers re-export flagging: some real (non-re-export) consumer
reaches `N` through the import / re-export graph, directly or transitively through barrels.

Chosen semantics: **"no external consumer = unused."** `isLive(F, N)` searches references to the
*module file*; same-file references (another local symbol, or `SomeFun.displayName = 'SomeFun'`) are
not file references, so they do not keep the export alive. Consequences:

- `SomeFun` / `Some` above → only ref is the dead barrel `export *` → not live → flagged. ✓
- A symbol used only internally by another (exported, externally consumed) symbol is flagged too:
  its `export` is redundant and could be made local. This is intended, not a false positive.

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
- **Message:** `"Export '<name>' is never used (no consumer reaches it)"`,
  `ProblemHighlightType.LIKE_UNUSED_SYMBOL`.

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
- A symbol referenced only in its own file (incl. `X.displayName = …`) → flagged (per the rule).
- `export { x } from './y'` is left to `DeadReExportInspection` — `DeadExportInspection` does not
  also flag it.

Mutation-check the new liveness path (force always-live / always-dead) to confirm the new tests
have teeth, as done for the re-export work.

## Out of scope

- Distinguishing "unused entirely" from "used only internally / can be made local" — the chosen
  rule collapses both into one flag.
- A quick-fix to delete or de-export the symbol (future).
