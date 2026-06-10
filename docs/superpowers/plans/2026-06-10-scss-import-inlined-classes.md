# SCSS `@import`-inlined classes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `styles.` completion, the unknown-class inspection, and the unused-class inspection account for classes that Sass inlines into a `.module.scss` via `@import` / `@use` / `@forward` (relative paths and the `@/` tsconfig alias), transitively.

**Architecture:** All resolution lives in `CssModules` (`CssModuleShared.kt`), resolved from source files (no TS service). A transitive class collector (`collectAllClassNames`) feeds completion + unknown-class. A cached module-import graph plus reverse reachability (`modulesTransitivelyImporting`) feeds a widened `collectUsedClassNames` for the unused-class inspection.

**Tech Stack:** Kotlin 2.3.0, IntelliJ Platform 2.6.0, JUnit4, `BasePlatformTestCase` against the local WebStorm SDK.

**Spec:** `docs/superpowers/specs/2026-06-10-scss-import-inlined-classes-design.md`

**Run tests:** `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test`
(abbreviated below as `./gradlew test`). A single class: `./gradlew test --tests "com.intch.cssmodules.CssScssImportLogicTest"`.

---

## Task 1: Pure SCSS-import path parser

**Files:**
- Modify: `src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt`
- Test: `src/test/kotlin/com/intch/cssmodules/CssScssImportLogicTest.kt` (create)

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/intch/cssmodules/CssScssImportLogicTest.kt`:

```kotlin
package com.intch.cssmodules

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for parsing `@import`/`@use`/`@forward` paths out of SCSS text. */
class CssScssImportLogicTest {

    @Test
    fun `extracts a single import path`() {
        assertEquals(
            listOf("@/src/containers/Onboarding/common.module.scss"),
            CssModules.scssImportPaths("""@import "@/src/containers/Onboarding/common.module.scss";"""),
        )
    }

    @Test
    fun `handles both quote styles`() {
        assertEquals(
            listOf("../a.module.scss", "./b.module.scss"),
            CssModules.scssImportPaths("@import '../a.module.scss';\n@import \"./b.module.scss\";"),
        )
    }

    @Test
    fun `handles a comma-separated import list`() {
        assertEquals(
            listOf("a.module.scss", "b.module.scss"),
            CssModules.scssImportPaths("""@import "a.module.scss", "b.module.scss";"""),
        )
    }

    @Test
    fun `handles use and forward with options`() {
        assertEquals(
            listOf("./theme.module.scss", "./vars"),
            CssModules.scssImportPaths("@use './theme.module.scss' as t;\n@forward './vars' with (\$x: 1);"),
        )
    }

    @Test
    fun `ignores non-import at-rules and plain text`() {
        assertEquals(
            emptyList<String>(),
            CssModules.scssImportPaths(".a { color: red; }\n@media screen { .b {} }"),
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.intch.cssmodules.CssScssImportLogicTest"`
Expected: FAIL — `scssImportPaths` is unresolved (compile error).

- [ ] **Step 3: Implement `scssImportPaths`**

In `CssModuleShared.kt`, inside `object CssModules`, add (place it near `isModuleFileName`):

```kotlin
    private val SCSS_IMPORT = Regex("""@(?:import|use|forward)\b([^;{}\n]*)""")
    private val QUOTED = Regex("""['"]([^'"]+)['"]""")

    /** All paths referenced by `@import`/`@use`/`@forward` in SCSS [text], in order. */
    fun scssImportPaths(text: String): List<String> =
        SCSS_IMPORT.findAll(text)
            .flatMap { m -> QUOTED.findAll(m.groupValues[1]).map { it.groupValues[1] } }
            .toList()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.intch.cssmodules.CssScssImportLogicTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt src/test/kotlin/com/intch/cssmodules/CssScssImportLogicTest.kt
git commit -m "feat(css): parse @import/@use/@forward paths from SCSS text"
```

---

## Task 2: Pure tsconfig path-alias parser

**Files:**
- Modify: `src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt`
- Test: `src/test/kotlin/com/intch/cssmodules/CssScssImportLogicTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `CssScssImportLogicTest.kt` (inside the class):

```kotlin
    @Test
    fun `parses baseUrl and a wildcard path mapping`() {
        val ts = """
            { "compilerOptions": { "baseUrl": ".", "paths": { "@/*": ["./*"] } } }
        """.trimIndent()
        val cfg = CssModules.tsconfigAliases(ts)
        assertEquals(".", cfg.baseUrl)
        assertEquals(mapOf("@/*" to "./*"), cfg.paths)
    }

    @Test
    fun `parses paths when baseUrl is absent`() {
        val ts = """{ "compilerOptions": { "paths": { "@/*": ["./*"], "~/*": ["./src/*"] } } }"""
        val cfg = CssModules.tsconfigAliases(ts)
        assertEquals(null, cfg.baseUrl)
        assertEquals(mapOf("@/*" to "./*", "~/*" to "./src/*"), cfg.paths)
    }

    @Test
    fun `empty config when no paths`() {
        val cfg = CssModules.tsconfigAliases("""{ "compilerOptions": { "strict": true } }""")
        assertEquals(null, cfg.baseUrl)
        assertEquals(emptyMap<String, String>(), cfg.paths)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.intch.cssmodules.CssScssImportLogicTest"`
Expected: FAIL — `tsconfigAliases` / `TsconfigAliases` unresolved.

- [ ] **Step 3: Implement the parser**

In `CssModuleShared.kt`, add (top-level in the file, after the `CssModules` object or above it — keep it package-internal):

```kotlin
/** Minimal view of a tsconfig's path aliasing: optional baseUrl + `paths` mappings (first target only). */
internal data class TsconfigAliases(val baseUrl: String?, val paths: Map<String, String>)
```

And inside `object CssModules`:

```kotlin
    private val TS_BASE_URL = Regex(""""baseUrl"\s*:\s*"([^"]+)"""")
    private val TS_PATHS_BLOCK = Regex(""""paths"\s*:\s*\{""")
    private val TS_PATH_ENTRY = Regex(""""([^"]+)"\s*:\s*\[\s*"([^"]+)"""")

    /** Parse `compilerOptions.baseUrl` and `compilerOptions.paths` (first target per key) from tsconfig text. */
    fun tsconfigAliases(text: String): TsconfigAliases {
        val baseUrl = TS_BASE_URL.find(text)?.groupValues?.get(1)
        val blockStart = TS_PATHS_BLOCK.find(text)?.range?.last ?: return TsconfigAliases(baseUrl, emptyMap())
        // Take the balanced `{ ... }` that opens at blockStart.
        var depth = 0
        var end = blockStart
        for (i in blockStart until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        val block = text.substring(blockStart, end)
        val paths = TS_PATH_ENTRY.findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
        return TsconfigAliases(baseUrl, paths)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.intch.cssmodules.CssScssImportLogicTest"`
Expected: PASS (8 tests total).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt src/test/kotlin/com/intch/cssmodules/CssScssImportLogicTest.kt
git commit -m "feat(css): parse tsconfig baseUrl + path aliases"
```

---

## Task 3: Resolve a SCSS import path (relative + `@/` alias)

**Files:**
- Modify: `src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt`
- Test: `src/test/kotlin/com/intch/cssmodules/CssScssImportPsiTest.kt` (create)

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/intch/cssmodules/CssScssImportPsiTest.kt`:

```kotlin
package com.intch.cssmodules

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** PSI tests for SCSS import resolution + transitive class collection + the import graph. */
class CssScssImportPsiTest : BasePlatformTestCase() {

    // --- resolveImportPath -------------------------------------------------

    fun testResolvesRelativeSibling() {
        val common = myFixture.addFileToProject("src/a/common.module.scss", ".x {}")
        val from = myFixture.addFileToProject("src/a/Comp.module.scss", "")
        val dir = from.virtualFile.parent
        val resolved = CssModules.resolveImportPath(dir, project, "./common.module.scss")
        assertEquals(common.virtualFile, resolved)
    }

    fun testResolvesRelativeParent() {
        val common = myFixture.addFileToProject("src/common.module.scss", ".x {}")
        val from = myFixture.addFileToProject("src/a/Comp.module.scss", "")
        val dir = from.virtualFile.parent
        val resolved = CssModules.resolveImportPath(dir, project, "../common.module.scss")
        assertEquals(common.virtualFile, resolved)
    }

    fun testResolvesAtAlias() {
        myFixture.addFileToProject("tsconfig.json", """{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
        val common = myFixture.addFileToProject("src/containers/Onboarding/common.module.scss", ".x {}")
        val from = myFixture.addFileToProject("src/containers/Onboarding/sub/Comp.module.scss", "")
        val dir = from.virtualFile.parent
        val resolved = CssModules.resolveImportPath(dir, project, "@/src/containers/Onboarding/common.module.scss")
        assertEquals(common.virtualFile, resolved)
    }

    fun testUnresolvableReturnsNull() {
        val from = myFixture.addFileToProject("src/Comp.module.scss", "")
        val dir = from.virtualFile.parent
        assertNull(CssModules.resolveImportPath(dir, project, "@/does/not/exist.module.scss"))
        assertNull(CssModules.resolveImportPath(dir, project, "./nope.module.scss"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.intch.cssmodules.CssScssImportPsiTest"`
Expected: FAIL — `resolveImportPath` unresolved.

- [ ] **Step 3: Implement `resolveImportPath` + alias resolution**

In `CssModuleShared.kt`, add the import at the top:

```kotlin
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
```

Inside `object CssModules`, add:

```kotlin
    /**
     * Resolve a SCSS `@import`/`@use`/`@forward` [path] to a VirtualFile.
     * Relative (`.`/`..`/bare) resolve against [fromDir]; `@/`-style aliases resolve
     * via the nearest tsconfig's `paths`. Returns null if nothing resolves.
     */
    fun resolveImportPath(fromDir: VirtualFile, project: Project, path: String): VirtualFile? {
        if (path.startsWith(".")) return resolveRelative(fromDir, path)
        return resolveAlias(fromDir, path)
    }

    private fun resolveAlias(fromDir: VirtualFile, path: String): VirtualFile? {
        val tsconfig = findTsconfig(fromDir) ?: return null
        val text = runCatching { VfsUtilCore.loadText(tsconfig) }.getOrNull() ?: return null
        val cfg = tsconfigAliases(text)
        val baseDir = cfg.baseUrl
            ?.let { resolveRelative(tsconfig.parent, it) }
            ?: tsconfig.parent
            ?: return null
        for ((key, template) in cfg.paths) {
            val mapped = applyAlias(key, template, path) ?: continue
            resolveRelative(baseDir, mapped)?.let { return it }
        }
        return null
    }

    /** `@/*` + `./*` + `@/src/x` -> `./src/x`; exact (no `*`) maps when [path] equals [key]. */
    private fun applyAlias(key: String, template: String, path: String): String? {
        if (key.endsWith("/*")) {
            val prefix = key.dropLast(1) // "@/*" -> "@/"
            if (!path.startsWith(prefix)) return null
            val wildcard = path.substring(prefix.length)
            return template.replace("*", wildcard)
        }
        return if (path == key) template else null
    }

    /** Walk up from [start] looking for a `tsconfig.json`. */
    private fun findTsconfig(start: VirtualFile): VirtualFile? {
        var cur: VirtualFile? = start
        while (cur != null) {
            cur.findChild("tsconfig.json")?.let { return it }
            cur = cur.parent
        }
        return null
    }
```

Note: `resolveRelative` already exists and is `private` in `CssModules` — reuse it as-is.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.intch.cssmodules.CssScssImportPsiTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt src/test/kotlin/com/intch/cssmodules/CssScssImportPsiTest.kt
git commit -m "feat(css): resolve relative + @/ alias SCSS import paths"
```

---

## Task 4: Transitive class collection (`directModuleImports` + `collectAllClassNames`)

**Files:**
- Modify: `src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt`
- Test: `src/test/kotlin/com/intch/cssmodules/CssScssImportPsiTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `CssScssImportPsiTest.kt` (inside the class):

```kotlin
    // --- collectAllClassNames ---------------------------------------------

    fun testCollectAllIncludesImportedClasses() {
        myFixture.addFileToProject("src/common.module.scss", ".nextButton {} .note {}")
        val mod = myFixture.addFileToProject(
            "src/StepGraph.module.scss",
            "@import \"./common.module.scss\";\n.container {}",
        )
        assertEquals(setOf("container", "nextButton", "note"), CssModules.collectAllClassNames(mod).toSet())
    }

    fun testCollectAllIsTransitive() {
        myFixture.addFileToProject("src/c.module.scss", ".cc {}")
        myFixture.addFileToProject("src/b.module.scss", "@import \"./c.module.scss\";\n.bb {}")
        val a = myFixture.addFileToProject("src/a.module.scss", "@import \"./b.module.scss\";\n.aa {}")
        assertEquals(setOf("aa", "bb", "cc"), CssModules.collectAllClassNames(a).toSet())
    }

    fun testCollectAllSurvivesCycles() {
        myFixture.addFileToProject("src/a.module.scss", "@import \"./b.module.scss\";\n.aa {}")
        val b = myFixture.addFileToProject("src/b.module.scss", "@import \"./a.module.scss\";\n.bb {}")
        assertEquals(setOf("aa", "bb"), CssModules.collectAllClassNames(b).toSet())
    }

    fun testCollectAllIgnoresNonModuleImports() {
        myFixture.addFileToProject("src/vars.scss", ".globalThing {}")
        val mod = myFixture.addFileToProject(
            "src/Comp.module.scss",
            "@import \"./vars.scss\";\n.local {}",
        )
        assertEquals(setOf("local"), CssModules.collectAllClassNames(mod).toSet())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.intch.cssmodules.CssScssImportPsiTest"`
Expected: FAIL — `collectAllClassNames` unresolved.

- [ ] **Step 3: Implement the collectors**

In `CssModuleShared.kt`, add imports at the top:

```kotlin
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
```

Inside `object CssModules`, add:

```kotlin
    /** The CSS-module files directly imported by [scssFile] via `@import`/`@use`/`@forward`. */
    fun directModuleImports(scssFile: PsiFile): List<PsiFile> {
        val dir = scssFile.virtualFile?.parent ?: return emptyList()
        val project = scssFile.project
        val psiManager = PsiManager.getInstance(project)
        return scssImportPaths(scssFile.text).mapNotNull { path ->
            val vf = resolveImportPath(dir, project, path) ?: return@mapNotNull null
            if (!isModuleFileName(vf.name)) return@mapNotNull null
            psiManager.findFile(vf)
        }
    }

    /** Own class names plus those of every transitively imported CSS module (cycle-safe). */
    fun collectAllClassNames(moduleFile: PsiFile): List<String> =
        CachedValuesManager.getCachedValue(moduleFile) {
            val out = LinkedHashSet<String>()
            collectAllInto(moduleFile, out, HashSet())
            CachedValueProvider.Result.create(out.toList(), PsiModificationTracker.MODIFICATION_COUNT)
        }

    private fun collectAllInto(file: PsiFile, out: MutableSet<String>, visited: MutableSet<VirtualFile>) {
        val vf = file.virtualFile ?: return
        if (!visited.add(vf)) return
        out.addAll(collectClassNames(file))
        for (imported in directModuleImports(file)) collectAllInto(imported, out, visited)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.intch.cssmodules.CssScssImportPsiTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt src/test/kotlin/com/intch/cssmodules/CssScssImportPsiTest.kt
git commit -m "feat(css): collect transitively imported CSS-module class names"
```

---

## Task 5: Wire completion + unknown-class inspection to imported classes

**Files:**
- Modify: `src/main/kotlin/com/intch/cssmodules/CssModuleStylesCompletion.kt:58`
- Modify: `src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt` (`cssModuleBindings`, line ~66)
- Test: `src/test/kotlin/com/intch/cssmodules/CssModuleCompletionTest.kt`, `src/test/kotlin/com/intch/cssmodules/CssModuleInspectionTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `CssModuleCompletionTest.kt` (inside the class):

```kotlin
    fun testCompletesImportedClassesFromAtImport() {
        myFixture.addFileToProject("common.module.scss", ".nextButton { } .note { }")
        myFixture.addFileToProject(
            "Comp.module.scss",
            "@import './common.module.scss';\n.local { }",
        )
        myFixture.configureByText(
            "Comp.tsx",
            "import styles from './Comp.module.scss';\nfunction f() { return styles.<caret>; }",
        )
        myFixture.completeBasic()
        val suggestions = myFixture.lookupElementStrings ?: emptyList()
        assertTrue(
            "expected own + imported classes, got: $suggestions",
            suggestions.containsAll(listOf("local", "nextButton", "note")),
        )
    }
```

Append to `CssModuleInspectionTest.kt` (inside the class, in the "Unknown CSS module class" section):

```kotlin
    fun testImportedClassIsNotFlaggedAsUnknown() {
        myFixture.addFileToProject("src/common.module.scss", ".nextButton { color: red; }")
        myFixture.addFileToProject(
            "src/Comp.module.scss",
            "@import './common.module.scss';\n.local { color: blue; }",
        )
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            """
            import styles from './Comp.module.scss';
            const a = styles.nextButton;
            const b = styles.local;
            const c = styles.doesNotExist;
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(CssModuleUnknownClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "imported class 'nextButton' must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'nextButton'") },
        )
        assertTrue(
            "expected 'doesNotExist' still flagged, got: $descriptions",
            descriptions.any { it.contains("Unknown CSS module class 'doesNotExist'") },
        )
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.intch.cssmodules.CssModuleCompletionTest" --tests "com.intch.cssmodules.CssModuleInspectionTest"`
Expected: FAIL — imported classes neither completed nor recognised (still using own-only `collectClassNames`).

- [ ] **Step 3: Switch both consumers to `collectAllClassNames`**

In `CssModuleShared.kt`, in `cssModuleBindings`, change the line that builds the class set:

```kotlin
            result[binding] = collectAllClassNames(psi).toSet()
```

(was `collectClassNames(psi).toSet()`).

In `CssModuleStylesCompletion.kt`, in `fillMemberCompletion`, change:

```kotlin
        val classes = moduleFile?.let { CssModules.collectAllClassNames(it) } ?: emptyList()
```

(was `CssModules.collectClassNames(it)`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.intch.cssmodules.CssModuleCompletionTest" --tests "com.intch.cssmodules.CssModuleInspectionTest"`
Expected: PASS (all existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt src/main/kotlin/com/intch/cssmodules/CssModuleStylesCompletion.kt src/test/kotlin/com/intch/cssmodules/CssModuleCompletionTest.kt src/test/kotlin/com/intch/cssmodules/CssModuleInspectionTest.kt
git commit -m "feat(css): complete & accept @import-inlined classes in styles.*"
```

---

## Task 6: Module-import graph + reverse reachability

**Files:**
- Modify: `src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt`
- Test: `src/test/kotlin/com/intch/cssmodules/CssScssImportPsiTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `CssScssImportPsiTest.kt` (inside the class):

```kotlin
    // --- modulesTransitivelyImporting -------------------------------------

    fun testReverseReachabilityFindsDirectAndTransitiveImporters() {
        val common = myFixture.addFileToProject("src/common.module.scss", ".x {}")
        val mid = myFixture.addFileToProject("src/Mid.module.scss", "@import './common.module.scss';\n.m {}")
        val top = myFixture.addFileToProject("src/Top.module.scss", "@import './Mid.module.scss';\n.t {}")
        myFixture.addFileToProject("src/Unrelated.module.scss", ".u {}")

        val importers = CssModules.modulesTransitivelyImporting(common)
        assertEquals(
            setOf(common.virtualFile, mid.virtualFile, top.virtualFile),
            importers,
        )
    }

    fun testReverseReachabilityIsJustSelfWhenNobodyImports() {
        val lonely = myFixture.addFileToProject("src/Lonely.module.scss", ".x {}")
        assertEquals(setOf(lonely.virtualFile), CssModules.modulesTransitivelyImporting(lonely))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.intch.cssmodules.CssScssImportPsiTest"`
Expected: FAIL — `modulesTransitivelyImporting` unresolved.

- [ ] **Step 3: Implement the graph + reverse reachability**

In `CssModuleShared.kt`, add imports at the top:

```kotlin
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
```

(`Project` may already be imported from Task 3; keep a single import. `GlobalSearchScope` is already imported.)

Inside `object CssModules`, add:

```kotlin
    private val CSS_EXTS = listOf("scss", "sass", "less", "css")

    /** Forward graph over all CSS-module files: file -> the CSS-module files it directly imports. */
    fun moduleImportGraph(project: Project): Map<VirtualFile, Set<VirtualFile>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val psiManager = PsiManager.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)
            val graph = HashMap<VirtualFile, Set<VirtualFile>>()
            for (ext in CSS_EXTS) {
                for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                    if (!isModuleFileName(vf.name)) continue
                    val psi = psiManager.findFile(vf) ?: continue
                    graph[vf] = directModuleImports(psi).mapNotNull { it.virtualFile }.toSet()
                }
            }
            CachedValueProvider.Result.create(graph, PsiModificationTracker.MODIFICATION_COUNT)
        }

    /** [moduleFile] plus every CSS module that transitively `@import`s it. */
    fun modulesTransitivelyImporting(moduleFile: PsiFile): Set<VirtualFile> {
        val target = moduleFile.virtualFile ?: return emptySet()
        val graph = moduleImportGraph(moduleFile.project)
        val reached = hashSetOf(target)
        var changed = true
        while (changed) {
            changed = false
            for ((importer, targets) in graph) {
                if (importer in reached) continue
                if (targets.any { it in reached }) { reached.add(importer); changed = true }
            }
        }
        return reached
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.intch.cssmodules.CssScssImportPsiTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt src/test/kotlin/com/intch/cssmodules/CssScssImportPsiTest.kt
git commit -m "feat(css): build CSS-module import graph + reverse reachability"
```

---

## Task 7: Widen the unused-class inspection for inlined classes

**Files:**
- Modify: `src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt` (`collectUsedClassNames` + new `hasConsumingImporter`)
- Modify: `src/main/kotlin/com/intch/cssmodules/CssModuleUnusedClassInspection.kt:20-22`
- Test: `src/test/kotlin/com/intch/cssmodules/CssModuleInspectionTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `CssModuleInspectionTest.kt` (inside the class, in the "Unused" section):

```kotlin
    fun testImportedClassUsedViaConsumerIsNotUnused() {
        // common is imported (not by JS) into Comp.module.scss, used as styles.shared in Comp.tsx.
        val common = myFixture.addFileToProject(
            "src/common.module.scss",
            ".shared { color: red; }\n.deadInCommon { color: green; }",
        )
        myFixture.addFileToProject(
            "src/Comp.module.scss",
            "@import './common.module.scss';\n.local { color: blue; }",
        )
        myFixture.addFileToProject(
            "src/Comp.tsx",
            "import styles from './Comp.module.scss';\nconst a = styles.shared;\nconst b = styles.local;",
        )
        myFixture.configureFromExistingVirtualFile(common.virtualFile)
        myFixture.enableInspections(CssModuleUnusedClassInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "'shared' is used via a consumer and must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'shared'") },
        )
        assertTrue(
            "'deadInCommon' is never referenced and SHOULD be flagged, got: $descriptions",
            descriptions.any { it.contains("'deadInCommon'") && it.contains("not used") },
        )
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.intch.cssmodules.CssModuleInspectionTest"`
Expected: FAIL — `'shared'` is flagged unused (current `collectUsedClassNames` finds no JS importer of `common`).

- [ ] **Step 3: Rewrite `collectUsedClassNames` + add `hasConsumingImporter`; update the guard**

In `CssModuleShared.kt`, replace the existing `collectUsedClassNames` body with the transitive version and add `hasConsumingImporter`:

```kotlin
    /**
     * Class names of [moduleFile] actually referenced as `<binding>.<name>` in any JS
     * file that imports [moduleFile] OR any CSS module that transitively `@import`s it
     * (Sass inlines those classes, so they're exported on the consumer's `styles`).
     */
    fun collectUsedClassNames(moduleFile: PsiFile): Set<String> {
        val ownClasses = collectClassNames(moduleFile).toSet()
        if (ownClasses.isEmpty()) return emptySet()
        val psiManager = PsiManager.getInstance(moduleFile.project)
        val used = HashSet<String>()
        for (vf in modulesTransitivelyImporting(moduleFile)) {
            val consumer = psiManager.findFile(vf) ?: continue
            for ((file, bindings) in findImporters(consumer)) {
                PsiTreeUtil.collectElements(file) { it.firstChild == null && it.text == "." }
                    .forEach { dot ->
                        val qualifier = prevMeaningfulLeaf(dot) ?: return@forEach
                        if (qualifier.text !in bindings) return@forEach
                        val member = nextMeaningfulLeaf(dot) ?: return@forEach
                        if (member.text in ownClasses) used.add(member.text)
                    }
            }
        }
        return used
    }

    /** True if [moduleFile] (or any module that transitively imports it) is imported by a JS file. */
    fun hasConsumingImporter(moduleFile: PsiFile): Boolean {
        val psiManager = PsiManager.getInstance(moduleFile.project)
        return modulesTransitivelyImporting(moduleFile).any { vf ->
            val psi = psiManager.findFile(vf) ?: return@any false
            findImporters(psi).isNotEmpty()
        }
    }
```

In `CssModuleUnusedClassInspection.kt`, replace the importer guard (lines ~20-22):

```kotlin
        // Nobody consumes this module (directly or via @import chain) -> can't tell what's used.
        if (!CssModules.hasConsumingImporter(file)) return PsiElementVisitor.EMPTY_VISITOR
```

(removes the `val importers = CssModules.findImporters(file)` / `if (importers.isEmpty())` lines; keep the subsequent `val used = CssModules.collectUsedClassNames(file)` line.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.intch.cssmodules.CssModuleInspectionTest"`
Expected: PASS (existing unused/unknown tests + the new one). In particular `testNoUnusedReportedWhenNoImporters` still passes (lonely module → `hasConsumingImporter` false → bail), and `testUnusedClassIsReported` still passes.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/intch/cssmodules/CssModuleShared.kt src/main/kotlin/com/intch/cssmodules/CssModuleUnusedClassInspection.kt src/test/kotlin/com/intch/cssmodules/CssModuleInspectionTest.kt
git commit -m "feat(css): count @import-inlined class usage across consumers in unused check"
```

---

## Task 8: Full suite, docs, version bump

**Files:**
- Modify: `build.gradle.kts:9` (version)
- Modify: `CHANGELOG.md`
- Modify: `README.md`

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew test`
Expected: PASS — all green (the 96 prior tests + the new ones: 8 pure in `CssScssImportLogicTest`, 10 PSI in `CssScssImportPsiTest`, 1 completion, 2 inspection).

- [ ] **Step 2: Bump the version**

In `build.gradle.kts`, change:

```kotlin
version = "1.4.0"
```

- [ ] **Step 3: Add a CHANGELOG entry**

Prepend a new section under the intro in `CHANGELOG.md` (above `## 1.3.0 — 2026-06-09`):

```markdown
## 1.4.0 — 2026-06-10
- **CSS Modules: follow Sass `@import`.** Classes Sass inlines into a module via
  `@import` / `@use` / `@forward` (relative paths and the `@/` tsconfig alias,
  transitively) now count as the module's own: `styles.<imported>` completes, is
  not flagged unknown, and — on the imported file — is not greyed as unused when a
  consumer references it. Real dead code in a shared file is still flagged.
  (`CssModules.scssImportPaths`/`resolveImportPath`/`collectAllClassNames`/
  `moduleImportGraph`/`modulesTransitivelyImporting`, widened `collectUsedClassNames`.)
- N tests.
```

(Replace `N` with the actual total reported by `./gradlew test`.)

- [ ] **Step 4: Update the README**

In `README.md`, update CSS-module feature items 4, 5, 6 to note imported classes
are included, and add a short note under "Architecture / where each thing lives"
mentioning the new `CssModules` helpers (`scssImportPaths`, `resolveImportPath`,
`collectAllClassNames`, `moduleImportGraph`, `modulesTransitivelyImporting`). Also
bump the test count line in the Tests section to match Step 1. Keep wording in the
existing terse style.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts CHANGELOG.md README.md
git commit -m "docs: 1.4.0 — follow Sass @import for CSS-module classes"
```

---

## Notes / gotchas for the implementer

- **`resolveRelative` is `private` in `CssModules`** — the new methods are in the
  same object, so they can call it directly. Don't duplicate it.
- **`Project` / `FilenameIndex` imports:** add each import exactly once at the top
  of `CssModuleShared.kt` even though several tasks reference them.
- **Caching:** `collectAllClassNames` and `moduleImportGraph` are cached on
  `PsiModificationTracker.MODIFICATION_COUNT`, so any edit invalidates them — this
  is what keeps a transitive result correct after editing an imported file. Don't
  use the file itself as the sole dependency.
- **Light-fixture file layout:** integration tests put `.tsx` + `.module.scss` at
  the source root (or under `src/`) so `./X.module.scss` resolves; the alias test
  adds a `tsconfig.json` at the fixture root.
- **tsgo independence:** every helper reads file text / generic PSI only — no TS
  service calls — matching the existing design.
```