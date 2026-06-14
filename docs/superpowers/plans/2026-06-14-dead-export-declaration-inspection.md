# Dead export declaration inspection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `DeadExportInspection` that greys a directly-declared export (`export const/function/class`, `export default`, local `export { x }`, `export interface/type/enum`) when no real consumer reaches it through the import/re-export graph.

**Architecture:** A new `LocalInspectionTool` in `com.webdx.deadexports`, sibling to `DeadReExportInspection`. It reuses `DeadReExports.Analyzer.isLive(file, name)` for liveness (same reverse reachability). The existing inspection keeps owning `… from` re-export links; the new one owns directly-declared exports. Liveness is "no external consumer" — same-file references do not count.

**Tech Stack:** Kotlin, IntelliJ Platform / JavaScript plugin SDK. PSI facts verified against the SDK:
- Inline `export const/function/class/interface/type/enum` → the declared element is a `JSPsiNamedElementBase` with `ES6ImportHandler.isExportedDirectly(el) == true` (and is NOT wrapped in an `ES6ExportDeclaration`).
- `export default …` → `ES6ExportDefaultAssignment`; `getNamedElement()` gives the named child (may be null for anonymous).
- Local `export { x as y }` → `ES6ExportSpecifier` whose parent `ES6ExportDeclaration` has a null `fromClause`; `isExportedDirectly` on the underlying variable is `false`, so it is handled via the specifier only (no double-flag).
- `export { x } from` / `export *` → `isReExport` / `isExportAll`; owned by `DeadReExportInspection`, must be ignored here.

---

### Task 1: Failing test — unused inline value exports flagged

**Files:**
- Create: `src/test/kotlin/com/webdx/deadexports/DeadExportInspectionTest.kt`

- [ ] **Step 1: Write the test file with the first failing test**

```kotlin
package com.webdx.deadexports

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DeadExportInspectionTest : BasePlatformTestCase() {

    private fun descriptionsFor(path: String): List<String> {
        val file = myFixture.findFileInTempDir(path)
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.enableInspections(DeadExportInspection())
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    fun testUnusedInlineValueExportsFlagged() {
        // SomeFun + Some are exported but reached only by a dead barrel `export *` -> flagged.
        myFixture.addFileToProject("components/SomeFun.tsx",
            "export const SomeFun = () => null\nSomeFun.displayName = 'SomeFun'\nexport function Some() {}\n")
        myFixture.addFileToProject("components/index.ts", "export * from './SomeFun'\n")
        val descriptions = descriptionsFor("components/SomeFun.tsx")
        assertTrue("SomeFun should be flagged, got: $descriptions",
            descriptions.any { it.contains("'SomeFun'") && it.contains("never used") })
        assertTrue("Some should be flagged, got: $descriptions",
            descriptions.any { it.contains("'Some'") && it.contains("never used") })
    }
}
```

- [ ] **Step 2: Run it to verify it fails to compile (class missing)**

Run: `./gradlew test --tests "com.webdx.deadexports.DeadExportInspectionTest.testUnusedInlineValueExportsFlagged"`
Expected: FAIL — compilation error, `DeadExportInspection` is unresolved.

---

### Task 2: Implement `DeadExportInspection` (inline declared exports)

**Files:**
- Create: `src/main/kotlin/com/webdx/deadexports/DeadExportInspection.kt`

- [ ] **Step 1: Write the inspection**

```kotlin
package com.webdx.deadexports

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ExportSpecifier
import com.intellij.lang.ecmascript6.resolve.ES6ImportHandler
import com.intellij.lang.javascript.psi.JSPsiNamedElementBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil

/**
 * Greys a directly-declared export — `export const/function/class`, `export default`, a local
 * `export { x }`, or `export interface/type/enum` — whose exported name is never reached by any
 * real (non-re-export) consumer through the import / re-export graph. Liveness is delegated to
 * [DeadReExports.Analyzer.isLive], which searches references to the *module file*, so same-file
 * uses (e.g. `SomeFun.displayName = 'SomeFun'`) do not keep an export alive. The `… from`
 * re-export links themselves are owned by [DeadReExportInspection].
 */
class DeadExportInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val moduleFile = holder.file.originalFile
        if (NextEntryPoints.isEntryPoint(moduleFile)) return PsiElementVisitor.EMPTY_VISITOR
        val analyzer = DeadReExports.Analyzer(moduleFile.project)

        fun flag(name: String, anchor: PsiElement) {
            if (!analyzer.isLive(moduleFile, name)) {
                holder.registerProblem(anchor, "Export '$name' is never used (no consumer reaches it)",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL)
            }
        }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    // Local `export { x as y }` only. Re-exports (`… from`) belong to DeadReExportInspection.
                    is ES6ExportSpecifier -> {
                        val decl = PsiTreeUtil.getParentOfType(element, ES6ExportDeclaration::class.java, false)
                        if (decl == null || decl.fromClause != null) return
                        val name = element.declaredName ?: return
                        val anchor = element.alias?.nameIdentifier ?: element.referenceNameElement ?: element
                        flag(name, anchor)
                    }
                    // Inline `export const/function/class/interface/type/enum`.
                    is JSPsiNamedElementBase -> {
                        if (!ES6ImportHandler.isExportedDirectly(element)) return
                        val name = element.name ?: return
                        val anchor = (element as? PsiNameIdentifierOwner)?.nameIdentifier ?: element
                        flag(name, anchor)
                    }
                }
            }
        }
    }
}
```

Note: `ES6ExportDefaultAssignment` is handled in Task 5 (added to this `when`). `ES6ExportSpecifier` is a `JSPsiNamedElementBase`, so its branch MUST stay above the `JSPsiNamedElementBase` branch.

- [ ] **Step 2: Run the Task 1 test to verify it passes**

Run: `./gradlew test --tests "com.webdx.deadexports.DeadExportInspectionTest.testUnusedInlineValueExportsFlagged"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/webdx/deadexports/DeadExportInspection.kt src/test/kotlin/com/webdx/deadexports/DeadExportInspectionTest.kt
git commit -m "feat(deadexports): flag unused directly-declared exports"
```

---

### Task 3: Live export not flagged (named import)

**Files:**
- Modify: `src/test/kotlin/com/webdx/deadexports/DeadExportInspectionTest.kt`

- [ ] **Step 1: Add the test**

```kotlin
    fun testLiveInlineExportNotFlagged() {
        // A real named import keeps the export live -> not flagged.
        myFixture.addFileToProject("components/SomeFun.tsx", "export const SomeFun = () => null\n")
        myFixture.addFileToProject("components/index.ts", "export * from './SomeFun'\n")
        myFixture.addFileToProject("use.tsx", "import { SomeFun } from './components'\nconst x = SomeFun\n")
        val descriptions = descriptionsFor("components/SomeFun.tsx")
        assertFalse("live export must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }
```

- [ ] **Step 2: Run it to verify it passes**

Run: `./gradlew test --tests "com.webdx.deadexports.DeadExportInspectionTest.testLiveInlineExportNotFlagged"`
Expected: PASS (reachability flows SomeFun.tsx → barrel `export *` → named import).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/webdx/deadexports/DeadExportInspectionTest.kt
git commit -m "test(deadexports): live inline export is not flagged"
```

---

### Task 4: Register the inspection in plugin.xml

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml` (insert after the `DeadReExportJsxHarmony` block, before `</extensions>` at the end of the inspections `<extensions defaultExtensionNs="com.intellij">` group — i.e. right after the line `implementationClass="com.webdx.deadexports.DeadReExportInspection"/>` that closes the `DeadReExportJsxHarmony` entry)

- [ ] **Step 1: Add five registration entries (one per language), mirroring the DeadReExport block**

```xml
        <localInspection language="TypeScript JSX"
            shortName="DeadExportTsx"
            displayName="Unused export (no consumer reaches it)"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.deadexports.DeadExportInspection"/>
        <localInspection language="TypeScript"
            shortName="DeadExportTs"
            displayName="Unused export (no consumer reaches it)"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.deadexports.DeadExportInspection"/>
        <localInspection language="JavaScript"
            shortName="DeadExportJs"
            displayName="Unused export (no consumer reaches it)"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.deadexports.DeadExportInspection"/>
        <localInspection language="ECMAScript 6"
            shortName="DeadExportEs6"
            displayName="Unused export (no consumer reaches it)"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.deadexports.DeadExportInspection"/>
        <localInspection language="JSX Harmony"
            shortName="DeadExportJsxHarmony"
            displayName="Unused export (no consumer reaches it)"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.deadexports.DeadExportInspection"/>
```

- [ ] **Step 2: Build the plugin to verify the descriptor is valid**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL (plugin.xml parses, no duplicate shortName).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(deadexports): register DeadExportInspection for JS/TS languages"
```

---

### Task 5: Default exports — flagged when unused, skipped for Next.js pages

**Files:**
- Modify: `src/main/kotlin/com/webdx/deadexports/DeadExportInspection.kt`
- Modify: `src/test/kotlin/com/webdx/deadexports/DeadExportInspectionTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
    fun testUnusedDefaultExportFlagged() {
        myFixture.addFileToProject("widget.tsx", "export default function Widget() { return null }\n")
        val descriptions = descriptionsFor("widget.tsx")
        assertTrue("unused default export should be flagged, got: $descriptions",
            descriptions.any { it.contains("'default'") && it.contains("never used") })
    }

    fun testNextPageDefaultNotFlagged() {
        // A Next.js page default is an entry point (next.config present) -> whole file skipped.
        myFixture.addFileToProject("next.config.js", "module.exports = {}\n")
        myFixture.addFileToProject("pages/p/index.tsx", "export default function Page() { return null }\n")
        val descriptions = descriptionsFor("pages/p/index.tsx")
        assertFalse("Next.js page default must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }
```

- [ ] **Step 2: Run to verify the first fails**

Run: `./gradlew test --tests "com.webdx.deadexports.DeadExportInspectionTest.testUnusedDefaultExportFlagged"`
Expected: FAIL — default exports not yet handled (no `'default'` problem registered).

- [ ] **Step 3: Add the default-assignment branch to the `when`**

In `DeadExportInspection.visitElement`, add this branch as the FIRST branch of the `when` (before `is ES6ExportSpecifier`) and add the import `com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment`:

```kotlin
                    is ES6ExportDefaultAssignment -> {
                        val named = element.namedElement
                        val anchor = (named as? PsiNameIdentifierOwner)?.nameIdentifier ?: named ?: element
                        flag("default", anchor)
                    }
```

- [ ] **Step 4: Run both new tests to verify they pass**

Run: `./gradlew test --tests "com.webdx.deadexports.DeadExportInspectionTest.testUnusedDefaultExportFlagged" --tests "com.webdx.deadexports.DeadExportInspectionTest.testNextPageDefaultNotFlagged"`
Expected: PASS for both (the Next page is skipped by the existing `NextEntryPoints.isEntryPoint` guard in `buildVisitor`).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/deadexports/DeadExportInspection.kt src/test/kotlin/com/webdx/deadexports/DeadExportInspectionTest.kt
git commit -m "feat(deadexports): flag unused default exports; skip Next.js page defaults"
```

---

### Task 6: Local `export { x as y }` specifiers

**Files:**
- Modify: `src/test/kotlin/com/webdx/deadexports/DeadExportInspectionTest.kt`

- [ ] **Step 1: Write the tests**

```kotlin
    fun testUnusedLocalExportSpecifierFlagged() {
        // `const local = 1; export { local as pub }` — exported name is `pub`, unused -> flagged.
        myFixture.addFileToProject("m.ts", "const local = 1\nexport { local as pub }\n")
        val descriptions = descriptionsFor("m.ts")
        assertTrue("unused local re-export 'pub' should be flagged, got: $descriptions",
            descriptions.any { it.contains("'pub'") && it.contains("never used") })
        assertFalse("must flag the exported name 'pub', not the source 'local', got: $descriptions",
            descriptions.any { it.contains("'local'") })
    }

    fun testUsedLocalExportSpecifierNotFlagged() {
        myFixture.addFileToProject("m.ts", "const local = 1\nexport { local as pub }\n")
        myFixture.addFileToProject("use.ts", "import { pub } from './m'\nconst x = pub\n")
        val descriptions = descriptionsFor("m.ts")
        assertFalse("consumed local re-export must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }
```

- [ ] **Step 2: Run to verify they pass**

Run: `./gradlew test --tests "com.webdx.deadexports.DeadExportInspectionTest.testUnusedLocalExportSpecifierFlagged" --tests "com.webdx.deadexports.DeadExportInspectionTest.testUsedLocalExportSpecifierNotFlagged"`
Expected: PASS (the `ES6ExportSpecifier` branch from Task 2 already handles this; `local` is `isExportedDirectly=false` so it is not double-flagged via the named-element branch, and the query uses the exported name `pub`).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/webdx/deadexports/DeadExportInspectionTest.kt
git commit -m "test(deadexports): local export specifiers flagged by exported name"
```

---

### Task 7: TS types + re-export non-overlap

**Files:**
- Modify: `src/test/kotlin/com/webdx/deadexports/DeadExportInspectionTest.kt`

- [ ] **Step 1: Write the tests**

```kotlin
    fun testUnusedTypeExportsFlagged() {
        myFixture.addFileToProject("types.ts",
            "export interface IFace {}\nexport type TAlias = number\nexport enum E { X }\n")
        val descriptions = descriptionsFor("types.ts")
        assertTrue("unused interface should be flagged, got: $descriptions",
            descriptions.any { it.contains("'IFace'") && it.contains("never used") })
        assertTrue("unused type alias should be flagged, got: $descriptions",
            descriptions.any { it.contains("'TAlias'") && it.contains("never used") })
        assertTrue("unused enum should be flagged, got: $descriptions",
            descriptions.any { it.contains("'E'") && it.contains("never used") })
    }

    fun testUsedTypeExportNotFlagged() {
        myFixture.addFileToProject("types.ts", "export interface IFace { a: number }\n")
        myFixture.addFileToProject("use.ts", "import type { IFace } from './types'\nconst x: IFace = { a: 1 }\n")
        val descriptions = descriptionsFor("types.ts")
        assertFalse("used type must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }

    fun testReExportFromNotFlaggedByThisInspection() {
        // `export { x } from './y'` is a re-export link — owned by DeadReExportInspection, not this one.
        myFixture.addFileToProject("y.ts", "export const x = 1\n")
        myFixture.addFileToProject("barrel.ts", "export { x } from './y'\n")
        val descriptions = descriptionsFor("barrel.ts")
        assertFalse("DeadExportInspection must NOT flag a `… from` re-export, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }
```

- [ ] **Step 2: Run to verify they pass**

Run: `./gradlew test --tests "com.webdx.deadexports.DeadExportInspectionTest.testUnusedTypeExportsFlagged" --tests "com.webdx.deadexports.DeadExportInspectionTest.testUsedTypeExportNotFlagged" --tests "com.webdx.deadexports.DeadExportInspectionTest.testReExportFromNotFlaggedByThisInspection"`
Expected: PASS (types are `isExportedDirectly=true`; the `… from` specifier is skipped because its parent `ES6ExportDeclaration.fromClause != null`).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/webdx/deadexports/DeadExportInspectionTest.kt
git commit -m "test(deadexports): TS type exports flagged; `… from` re-exports left to sibling inspection"
```

---

### Task 8: Same-file-only use is still flagged (the chosen rule)

**Files:**
- Modify: `src/test/kotlin/com/webdx/deadexports/DeadExportInspectionTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
    fun testSameFileOnlyUseStillFlagged() {
        // `helper` is exported but used only inside its own module; no external consumer -> flagged
        // (chosen "no external consumer = unused" rule). `Main` is consumed externally -> live.
        myFixture.addFileToProject("m.ts",
            "export const helper = () => 1\nexport const Main = () => helper()\n")
        myFixture.addFileToProject("use.ts", "import { Main } from './m'\nconst x = Main\n")
        val descriptions = descriptionsFor("m.ts")
        assertTrue("internally-only-used export must be flagged, got: $descriptions",
            descriptions.any { it.contains("'helper'") && it.contains("never used") })
        assertFalse("externally-consumed export must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'Main'") && it.contains("never used") })
    }
```

- [ ] **Step 2: Run to verify it passes**

Run: `./gradlew test --tests "com.webdx.deadexports.DeadExportInspectionTest.testSameFileOnlyUseStillFlagged"`
Expected: PASS (`isLive(m.ts, "helper")` is false — the only file reference consumes `Main`, not `helper`; the internal call is not a file reference).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/webdx/deadexports/DeadExportInspectionTest.kt
git commit -m "test(deadexports): export used only same-file is flagged (no external consumer)"
```

---

### Task 9: Mutation check + full suite + docs

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Mutation check — force always-live, confirm "dead" tests fail**

Temporarily edit `DeadExportInspection.flag` so the guard never fires:

```kotlin
        fun flag(name: String, anchor: PsiElement) {
            if (true) return // MUTANT
            if (!analyzer.isLive(moduleFile, name)) {
```

Run: `./gradlew test --tests "com.webdx.deadexports.DeadExportInspectionTest"`
Expected: the "flagged" tests FAIL (testUnusedInlineValueExportsFlagged, testUnusedDefaultExportFlagged, testUnusedLocalExportSpecifierFlagged, testUnusedTypeExportsFlagged, testSameFileOnlyUseStillFlagged). Then REMOVE the `if (true) return // MUTANT` line.

- [ ] **Step 2: Mutation check — force always-dead, confirm "live" tests fail**

Temporarily replace the guard with `if (false) { ... }` equivalent — change `if (!analyzer.isLive(moduleFile, name))` to `if (true)`:

```kotlin
            if (true) { // MUTANT: flag everything
                holder.registerProblem(anchor, "Export '$name' is never used (no consumer reaches it)",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL)
            }
```

Run: `./gradlew test --tests "com.webdx.deadexports.DeadExportInspectionTest"`
Expected: the "not flagged" tests FAIL (testLiveInlineExportNotFlagged, testNextPageDefaultNotFlagged, testUsedLocalExportSpecifierNotFlagged, testUsedTypeExportNotFlagged, testReExportFromNotFlaggedByThisInspection). Then RESTORE the original guard (`if (!analyzer.isLive(moduleFile, name))`).

- [ ] **Step 3: Run the full suite to confirm green after restore**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Document the new inspection in README**

In `README.md`, immediately after the "Dead re-exports / dead barrels" item (the paragraph ending with the `@/` path-alias note), add:

```markdown
18. **"Unused export" inspection** (`com.webdx.deadexports.DeadExportInspection`). Greys a
    **directly-declared** export — `export const/let/var`, `export function`, `export class`,
    `export default`, a local `export { x as y }`, or `export interface/type/enum` — when **no
    other module** reaches its exported name through the import/re-export graph (reusing the same
    reverse reachability as the dead re-export inspection). Same-file references (e.g.
    `SomeFun.displayName = 'SomeFun'`, or another local symbol) do not count as usage, so an
    export consumed only by a dead barrel link is flagged. Next.js page/app entry points are
    excluded. The `… from` re-export links themselves remain owned by the dead re-export
    inspection above.
```

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs(deadexports): document the unused-export inspection"
```

---

## Self-Review notes

- **Spec coverage:** value exports (Tasks 1–2, 6), default exports + Next exclusion (Task 5), TS types (Task 7), liveness via `isLive` / no-external-consumer rule (Tasks 1, 8), re-export non-overlap (Task 7), registration (Task 4), conservatism inherited from `Analyzer` (no new code), testing + mutation (all tasks + Task 9). All spec sections map to a task.
- **Type consistency:** the inspection class is `DeadExportInspection` throughout; the analyzer call is `analyzer.isLive(moduleFile, name)` (existing signature); the helper is `flag(name, anchor)` everywhere; `ES6ExportSpecifier` branch precedes the `JSPsiNamedElementBase` branch (required because a specifier is also a `JSPsiNamedElementBase`).
- **No placeholders:** every step shows complete code or an exact command + expected result.
