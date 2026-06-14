# Dead re-export inspection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A WebStorm inspection that greys an `export … from` re-export whose exported name is never reached by any real (non-re-export) consumer, even through chains of re-exports.

**Architecture:** A pure analysis object `DeadReExports` does reverse reachability over the re-export graph using the IDE's own module resolution (`ReferencesSearch.search(moduleFile)`), so `@/` path aliases and `require()` are handled for free. A thin `DeadReExportInspection : LocalInspectionTool` visits `ES6ExportDeclaration` re-export nodes and flags dead names. Registered per JS/TS language in `plugin.xml`, mirroring the existing `RnStyleUnusedKeyInspection`.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, IntelliJ JavaScript plugin PSI (`com.intellij.lang.ecmascript6.psi.*`), JUnit 3 via `BasePlatformTestCase`, Gradle.

---

## Verified SDK API (already confirmed against the bundled javascript-plugin.jar)

- `com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration`
  - `isReExport(): Boolean` — true when the declaration has a `from` clause
  - `isExportAll(): Boolean` — true for `export * from '…'`
  - `getExportSpecifiers(): Array<ES6ExportSpecifier>`
  - `getFromClause(): ES6FromClause?` (inherited from `ES6ImportExportDeclaration`)
  - `getImportExportPrefixKind(): ES6ImportExportDeclaration.ImportExportPrefixKind` (inherited)
- `com.intellij.lang.ecmascript6.psi.ES6ImportExportSpecifier` (base of `ES6ExportSpecifier`)
  - `getDeclaredName(): String?` — the name as it exists in the *source* module
  - `getName(): String?` — the publicly exported name (`b` for `a as b`; equals declaredName when no alias)
- `com.intellij.lang.ecmascript6.psi.ES6FromClause`
  - `resolveReferencedElements(): Collection<PsiElement>`
- `com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration` — the `import …` form
- `com.intellij.psi.search.searches.ReferencesSearch.search(element, scope)` — used the same way as `CssModuleShared.findImporters` (src/main/kotlin/com/webdx/cssmodules/CssModuleShared.kt:283).

If any accessor name differs at compile time, the Kotlin compiler error names the correct one — adjust in place. Do **not** invent additional members.

## File structure

- Create `src/main/kotlin/com/webdx/deadexports/DeadReExports.kt` — pure analysis (graph reachability + name enumeration). Unit-testable, no UI.
- Create `src/main/kotlin/com/webdx/deadexports/DeadReExportInspection.kt` — `LocalInspectionTool` visitor.
- Modify `src/main/resources/META-INF/plugin.xml` — five `<localInspection>` registrations.
- Create `src/test/kotlin/com/webdx/deadexports/DeadReExportsTest.kt` — logic-level tests.
- Create `src/test/kotlin/com/webdx/deadexports/DeadReExportInspectionTest.kt` — end-to-end highlighting tests.

## Semantics recap (from the approved spec)

- Per exported **name** of each `export … from`.
- A name `X` exported by module `F` is **live** if any reference to module `F` is a real consumer (`import`, `require(F)`, `require(F).X`, `import(F)`), or a re-export site whose own forwarding of `X` is transitively live.
- Conservative: any reference we cannot classify as a re-export is treated as a real consumer (→ live). `import * as`, whole-module `require(F)`, and `export * from F` keep **all** of `F`'s names live.
- Scope: `GlobalSearchScope.projectScope(project)`.

---

## Task 1: Enumerate the exported names of a re-export declaration

**Files:**
- Create: `src/main/kotlin/com/webdx/deadexports/DeadReExports.kt`
- Test: `src/test/kotlin/com/webdx/deadexports/DeadReExportsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.webdx.deadexports

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DeadReExportsTest : BasePlatformTestCase() {

    private fun reExportDecls(text: String): List<ES6ExportDeclaration> {
        val file = myFixture.configureByText("m.ts", text)
        return PsiTreeUtil.findChildrenOfType(file, ES6ExportDeclaration::class.java)
            .filter { it.isReExport }
    }

    fun testNamedReExportNames() {
        val decl = reExportDecls("export { a, b as c } from './x'").single()
        assertEquals(listOf("a", "b"), DeadReExports.reExportedSourceNames(decl))
    }

    fun testDefaultReExportName() {
        val decl = reExportDecls("export { default } from './x'").single()
        assertEquals(listOf("default"), DeadReExports.reExportedSourceNames(decl))
    }

    fun testExportAllIsStar() {
        val decl = reExportDecls("export * from './x'").single()
        assertEquals(listOf(DeadReExports.STAR), DeadReExports.reExportedSourceNames(decl))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'com.webdx.deadexports.DeadReExportsTest' --rerun-tasks`
Expected: FAIL — `DeadReExports` is unresolved.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
package com.webdx.deadexports

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration

/**
 * Reverse reachability over the ES6 re-export graph: a name re-exported by a module is
 * "dead" when no real (non-re-export) consumer reaches it, even through chains of
 * `export … from`. Leans on the IDE's module resolution (ReferencesSearch), so `@/`
 * path aliases and `require()` are handled the same way the editor resolves them.
 */
object DeadReExports {

    /** Sentinel standing for every name of a module (`export * from` / `import *` / `require(F)`). */
    const val STAR = "*"

    /**
     * Source-module names a re-export declaration forwards: the `declaredName` of each
     * specifier (the name as it exists in the source module), or [STAR] for `export *`.
     */
    fun reExportedSourceNames(decl: ES6ExportDeclaration): List<String> {
        if (decl.isExportAll) return listOf(STAR)
        return decl.exportSpecifiers.mapNotNull { it.declaredName }
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests 'com.webdx.deadexports.DeadReExportsTest' --rerun-tasks`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/deadexports/DeadReExports.kt src/test/kotlin/com/webdx/deadexports/DeadReExportsTest.kt
git commit -m "feat(deadexports): enumerate re-exported source names of an export-from"
```

---

## Task 2: Classify a single reference site to a module

A reference to module `F` (found via `ReferencesSearch`) is one of: a re-export site, a real consumer, or unknown. This task adds the classifier as a sealed result plus a function over one `PsiElement`.

**Files:**
- Modify: `src/main/kotlin/com/webdx/deadexports/DeadReExports.kt`
- Test: `src/test/kotlin/com/webdx/deadexports/DeadReExportsTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `DeadReExportsTest`:

```kotlin
    private fun classifyRefIn(consumer: String, moduleName: String = "x"): DeadReExports.RefKind {
        myFixture.addFileToProject("$moduleName.ts", "export const a = 1\nexport default 2\n")
        val file = myFixture.configureByText("consumer.ts", consumer)
        val moduleFile = myFixture.findFileInTempDir("$moduleName.ts")
            .let { com.intellij.psi.PsiManager.getInstance(project).findFile(it)!! }
        val refs = com.intellij.psi.search.searches.ReferencesSearch
            .search(moduleFile, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
            .findAll()
            .filter { it.element.containingFile == file }
        return DeadReExports.classify(refs.first().element)
    }

    fun testImportIsRealConsumer() {
        assertEquals(DeadReExports.RefKind.RealConsumer, classifyRefIn("import { a } from './x'\nconst y = a"))
    }

    fun testRequireIsRealConsumer() {
        assertEquals(DeadReExports.RefKind.RealConsumer, classifyRefIn("const a = require('./x').a"))
    }

    fun testReExportSiteIsReExport() {
        val kind = classifyRefIn("export { a } from './x'")
        assertTrue("expected ReExportSite, got $kind", kind is DeadReExports.RefKind.ReExportSite)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'com.webdx.deadexports.DeadReExportsTest' --rerun-tasks`
Expected: FAIL — `DeadReExports.classify` / `RefKind` unresolved.

- [ ] **Step 3: Write the minimal implementation**

Add to `DeadReExports.kt`:

```kotlin
import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
```

```kotlin
    sealed interface RefKind {
        /** A non-re-export use (import / require / dynamic import): keeps the name live. */
        object RealConsumer : RefKind
        /** A re-export that forwards the name onward; liveness depends on [decl]'s own consumers. */
        data class ReExportSite(val decl: ES6ExportDeclaration) : RefKind
    }

    /**
     * Classify one reference to a module file. Conservative: anything we cannot positively
     * identify as an `export … from` re-export is treated as a real consumer.
     */
    fun classify(refElement: PsiElement): RefKind {
        val decl = PsiTreeUtil.getParentOfType(refElement, ES6ImportExportDeclaration::class.java, false)
        if (decl is ES6ExportDeclaration && decl.isReExport) return RefKind.ReExportSite(decl)
        return RefKind.RealConsumer
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests 'com.webdx.deadexports.DeadReExportsTest' --rerun-tasks`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(deadexports): classify a module reference as re-export vs real consumer"
```

---

## Task 3: Transitive liveness (`isLive`) with cycle protection

**Files:**
- Modify: `src/main/kotlin/com/webdx/deadexports/DeadReExports.kt`
- Test: `src/test/kotlin/com/webdx/deadexports/DeadReExportsTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `DeadReExportsTest`. The helper resolves a module file and the analyzer answers `isLive`:

```kotlin
    private fun analyzer() = DeadReExports.Analyzer(project)

    private fun moduleFile(path: String): com.intellij.psi.PsiFile =
        com.intellij.psi.PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir(path))!!

    fun testDeadBarrelBypassedByDeepRequire() {
        // barrel re-exports the component; the only consumer requires the .tsx DIRECTLY.
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\nexport default Screen\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen'\n")
        myFixture.addFileToProject("nav.tsx", "const C = require('./a/Screen').Screen\n")
        myFixture.configureByText("trigger.ts", "")
        assertFalse(analyzer().isLive(moduleFile("a/index.ts"), "Screen"))
    }

    fun testBarrelKeptAliveByImport() {
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen'\n")
        myFixture.addFileToProject("use.ts", "import { Screen } from './a'\nconst x = Screen\n")
        myFixture.configureByText("trigger.ts", "")
        assertTrue(analyzer().isLive(moduleFile("a/index.ts"), "Screen"))
    }

    fun testTransitiveChainLiveRoot() {
        // leaf -> mid (re-export) -> top (re-export) -> real import of top.
        myFixture.addFileToProject("leaf.ts", "export const K = 1\n")
        myFixture.addFileToProject("mid.ts", "export { K } from './leaf'\n")
        myFixture.addFileToProject("top.ts", "export { K } from './mid'\n")
        myFixture.addFileToProject("use.ts", "import { K } from './top'\nconst x = K\n")
        myFixture.configureByText("trigger.ts", "")
        assertTrue("mid should be live via top's importer", analyzer().isLive(moduleFile("mid.ts"), "K"))
    }

    fun testTransitiveChainAllDead() {
        myFixture.addFileToProject("leaf.ts", "export const K = 1\n")
        myFixture.addFileToProject("mid.ts", "export { K } from './leaf'\n")
        myFixture.addFileToProject("top.ts", "export { K } from './mid'\n") // nobody imports top
        myFixture.configureByText("trigger.ts", "")
        assertFalse(analyzer().isLive(moduleFile("mid.ts"), "K"))
    }

    fun testCycleTerminates() {
        myFixture.addFileToProject("p.ts", "export { Z } from './q'\n")
        myFixture.addFileToProject("q.ts", "export { Z } from './p'\n") // mutual, no real consumer
        myFixture.configureByText("trigger.ts", "")
        assertFalse(analyzer().isLive(moduleFile("p.ts"), "Z"))
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'com.webdx.deadexports.DeadReExportsTest' --rerun-tasks`
Expected: FAIL — `DeadReExports.Analyzer` unresolved.

- [ ] **Step 3: Write the minimal implementation**

Add to `DeadReExports.kt`:

```kotlin
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
```

```kotlin
    /**
     * Stateful per-pass analyzer. Holds a memo so repeated `isLive` queries within one
     * inspection pass share work, and a visited set so re-export cycles terminate.
     */
    class Analyzer(private val project: Project) {
        private val scope = GlobalSearchScope.projectScope(project)
        private val memo = HashMap<Pair<String, String>, Boolean>()

        /** Is [name] (a name exported by [moduleFile]) reached by any real consumer? */
        fun isLive(moduleFile: PsiFile, name: String): Boolean =
            isLive(moduleFile, name, HashSet())

        private fun isLive(moduleFile: PsiFile, name: String, visited: MutableSet<Pair<String, String>>): Boolean {
            val origin = moduleFile.originalFile
            val key = (origin.virtualFile?.path ?: origin.name) to name
            memo[key]?.let { return it }
            if (!visited.add(key)) return false // cycle: this path contributes no new liveness

            var live = false
            for (ref in ReferencesSearch.search(origin, scope).findAll()) {
                when (val kind = classify(ref.element)) {
                    RefKind.RealConsumer -> { live = true; break }
                    is RefKind.ReExportSite -> {
                        val g = kind.decl.containingFile?.originalFile ?: continue
                        if (forwardsName(kind.decl, name)) {
                            for (forwarded in forwardedAs(kind.decl, name)) {
                                if (isLive(g, forwarded, visited)) { live = true; break }
                            }
                        }
                        if (live) break
                    }
                }
            }
            memo[key] = live
            return live
        }

        /** Does this re-export site forward our source [name] (directly or via `export *`)? */
        private fun forwardsName(decl: ES6ExportDeclaration, name: String): Boolean {
            if (decl.isExportAll) return true
            return decl.exportSpecifiers.any { it.declaredName == name }
        }

        /** The name(s) under which [name] leaves [decl] (the alias, or [name] itself for `export *`). */
        private fun forwardedAs(decl: ES6ExportDeclaration, name: String): List<String> {
            if (decl.isExportAll) return listOf(name)
            return decl.exportSpecifiers
                .filter { it.declaredName == name }
                .map { it.name ?: name }
        }
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests 'com.webdx.deadexports.DeadReExportsTest' --rerun-tasks`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(deadexports): transitive isLive over the re-export graph with cycle guard"
```

---

## Task 4: Conservative widening — `import *` / whole-module `require` keep all names live

`ReferencesSearch` already surfaces these as `RealConsumer` (Task 2 returns `RealConsumer` for anything that is not an `export … from`), so this task only adds tests pinning that behaviour and the `export *` consumer case.

**Files:**
- Test: `src/test/kotlin/com/webdx/deadexports/DeadReExportsTest.kt`

- [ ] **Step 1: Write the failing/locking tests**

```kotlin
    fun testNamespaceImportKeepsAllLive() {
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen'\n")
        myFixture.addFileToProject("use.ts", "import * as ns from './a'\nconst x = ns\n")
        myFixture.configureByText("trigger.ts", "")
        assertTrue(analyzer().isLive(moduleFile("a/index.ts"), "Screen"))
    }

    fun testExportStarConsumerKeepsLiveWhenItsRootIsLive() {
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen'\n")
        myFixture.addFileToProject("agg.ts", "export * from './a'\n")          // re-export site
        myFixture.addFileToProject("use.ts", "import { Screen } from './agg'\nconst x = Screen\n")
        myFixture.configureByText("trigger.ts", "")
        assertTrue(analyzer().isLive(moduleFile("a/index.ts"), "Screen"))
    }
```

- [ ] **Step 2: Run to verify**

Run: `./gradlew test --tests 'com.webdx.deadexports.DeadReExportsTest' --rerun-tasks`
Expected: PASS (13 tests). If `testExportStarConsumerKeepsLiveWhenItsRootIsLive` fails, the `forwardedAs` path for `export *` is wrong — confirm `forwardsName` returns true on `isExportAll` and `forwardedAs` returns `listOf(name)`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test(deadexports): pin namespace-import and export-star liveness"
```

---

## Task 5: The inspection + plugin.xml registration (end-to-end highlighting)

**Files:**
- Create: `src/main/kotlin/com/webdx/deadexports/DeadReExportInspection.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/webdx/deadexports/DeadReExportInspectionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.webdx.deadexports

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DeadReExportInspectionTest : BasePlatformTestCase() {

    private fun descriptionsFor(barrelPath: String): List<String> {
        val barrel = myFixture.findFileInTempDir(barrelPath)
        myFixture.configureFromExistingVirtualFile(barrel)
        myFixture.enableInspections(DeadReExportInspection())
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    fun testDeadBarrelFlagged() {
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\nexport default Screen\n")
        myFixture.addFileToProject("a/index.ts",
            "export { Screen } from './Screen'\nexport { default } from './Screen'\n")
        myFixture.addFileToProject("nav.tsx", "const C = require('./a/Screen').Screen\n") // bypasses barrel
        val descriptions = descriptionsFor("a/index.ts")
        assertTrue("Screen re-export should be flagged, got: $descriptions",
            descriptions.any { it.contains("'Screen'") && it.contains("never used") })
        assertTrue("default re-export should be flagged, got: $descriptions",
            descriptions.any { it.contains("'default'") && it.contains("never used") })
    }

    fun testLiveBarrelNotFlagged() {
        myFixture.addFileToProject("a/Screen.tsx", "export const Screen = () => null\n")
        myFixture.addFileToProject("a/index.ts", "export { Screen } from './Screen'\n")
        myFixture.addFileToProject("use.ts", "import { Screen } from './a'\nconst x = Screen\n")
        val descriptions = descriptionsFor("a/index.ts")
        assertFalse("live re-export must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'Screen'") && it.contains("never used") })
    }

    fun testPartiallyDeadBarrel() {
        myFixture.addFileToProject("a/x.ts", "export const Live = 1\nexport const Dead = 2\n")
        myFixture.addFileToProject("a/index.ts", "export { Live, Dead } from './x'\n")
        myFixture.addFileToProject("use.ts", "import { Live } from './a'\nconst x = Live\n")
        val descriptions = descriptionsFor("a/index.ts")
        assertTrue("Dead should be flagged, got: $descriptions",
            descriptions.any { it.contains("'Dead'") && it.contains("never used") })
        assertFalse("Live must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'Live'") && it.contains("never used") })
    }

    fun testNonReExportFileNoFlags() {
        myFixture.addFileToProject("plain.ts", "export const a = 1\nconst b = a\n")
        val descriptions = descriptionsFor("plain.ts")
        assertFalse("plain module -> nothing flagged, got: $descriptions",
            descriptions.any { it.contains("never used") })
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'com.webdx.deadexports.DeadReExportInspectionTest' --rerun-tasks`
Expected: FAIL — `DeadReExportInspection` unresolved.

- [ ] **Step 3: Write the inspection**

Create `src/main/kotlin/com/webdx/deadexports/DeadReExportInspection.kt`:

```kotlin
package com.webdx.deadexports

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Greys an `export … from` re-export whose exported name is never reached by any real
 * (non-re-export) consumer — a "dead barrel" link — even through chains of re-exports.
 * See com.webdx.deadexports.DeadReExports for the reachability analysis.
 */
class DeadReExportInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val moduleFile = holder.file.originalFile
        val analyzer = DeadReExports.Analyzer(moduleFile.project)
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is ES6ExportDeclaration || !element.isReExport) return
                if (element.isExportAll) {
                    // `export * from` re-exports the whole namespace; flag the statement only
                    // when the source module exports nothing live AND nothing reaches through it.
                    if (!analyzer.isLive(moduleFile, DeadReExports.STAR)) {
                        holder.registerProblem(element, "Re-export is never used (no consumer reaches it)",
                            ProblemHighlightType.LIKE_UNUSED_SYMBOL)
                    }
                    return
                }
                for (spec in element.exportSpecifiers) {
                    val sourceName = spec.declaredName ?: continue
                    if (!analyzer.isLive(moduleFile, sourceName)) {
                        val name = spec.name ?: sourceName
                        holder.registerProblem(spec, "Re-export '$name' is never used (no consumer reaches it)",
                            ProblemHighlightType.LIKE_UNUSED_SYMBOL)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Register in plugin.xml**

In `src/main/resources/META-INF/plugin.xml`, immediately after the last `RnStyleUnusedKey…` `<localInspection>` block (search for `RnStyleUnusedKeyEs6` / the `JSX Harmony` variant that follows it), add five registrations:

```xml
        <localInspection language="TypeScript JSX"
            shortName="DeadReExportTsx"
            displayName="Dead re-export (unused barrel link)"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.deadexports.DeadReExportInspection"/>
        <localInspection language="TypeScript"
            shortName="DeadReExportTs"
            displayName="Dead re-export (unused barrel link)"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.deadexports.DeadReExportInspection"/>
        <localInspection language="JavaScript"
            shortName="DeadReExportJs"
            displayName="Dead re-export (unused barrel link)"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.deadexports.DeadReExportInspection"/>
        <localInspection language="ECMAScript 6"
            shortName="DeadReExportEs6"
            displayName="Dead re-export (unused barrel link)"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.deadexports.DeadReExportInspection"/>
        <localInspection language="JSX Harmony"
            shortName="DeadReExportJsxHarmony"
            displayName="Dead re-export (unused barrel link)"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.deadexports.DeadReExportInspection"/>
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew test --tests 'com.webdx.deadexports.DeadReExportInspectionTest' --rerun-tasks`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(deadexports): DeadReExportInspection + plugin.xml registration"
```

---

## Task 6: Full suite + manual smoke check

**Files:** none (verification only).

- [ ] **Step 1: Run the whole test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL; the new `DeadReExports*` classes plus all pre-existing tests pass.

- [ ] **Step 2: Manual smoke check in a sandbox IDE (optional but recommended)**

Run: `./gradlew runIde`
Open the `intch_application` project, navigate to
`src/screens/ExpertsHubScreens/ExpertsHubInitialScreen/index.ts`, and confirm both
`export { ExpertsHubInitialScreen } from './ExpertsHubInitialScreen'` and
`export { default } from './ExpertsHubInitialScreen'` are greyed as unused, while a barrel
whose name is actually imported elsewhere is not.

- [ ] **Step 3: Commit any doc/CHANGELOG updates**

```bash
git add -A
git commit -m "docs(deadexports): note dead re-export inspection"
```

(Skip if there is nothing to record.)

---

## Self-review notes

- **Spec coverage:** transitive semantics (Task 3), per-name granularity (Task 5 visitor), real-consumer root incl. require (Tasks 2/3), conservative widening (Tasks 2/4), project scope + cycle guard (Task 3), highlight-only / no quick-fix (Task 5), five-language registration (Task 5). All spec sections map to a task.
- **Known limitation (documented, out of MVP scope):** a name consumed *only* through a fully dynamic `require(variablePath)` produces no resolvable reference to the module, so it cannot be seen; such a module could be flagged as dead. Accepted per the spec's MVP boundary; revisit if false positives appear.
- **Type consistency:** `Analyzer.isLive(PsiFile, String)`, `classify(PsiElement): RefKind`, `reExportedSourceNames(ES6ExportDeclaration): List<String>`, `STAR` constant — names are identical across all tasks that reference them.
