# SCSS Symbol Usage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Project-wide "unused SCSS symbol" inspection, Find Usages, and go-to-declaration for `$variables`, `@function`s, `@mixin`s, and `%placeholder`s, resolved through the real `@use`/`@import`/`@forward` import graph (full transitivity).

**Architecture:** A cached import-scope model (`ScssImportGraph`) + a symbol layer (`ScssSymbols`) that lists declarations/references (regex + `findElementAt`, no Sass-plugin PSI dependency) and `resolve`s each reference to its declaration via the graph. A cached project-wide `usesByDeclaration` map feeds the unused inspection and Find Usages; `resolve` feeds go-to. New package `com.webdx.scsssymbols`.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (base CSS PSI + VFS text scan), JUnit4 on `BasePlatformTestCase` against the local WebStorm SDK.

---

## Reference: verified facts

- The bam pattern proved regex-based SCSS parsing + `findElementAt` works without depending on Sass-plugin PSI classes (`com.webdx.cssmodules.BamSelectors`). This plan follows the same approach — do NOT import `org.jetbrains.plugins.scss.*` / `SassScss*Impl` classes (would add a compile dependency).
- `CssModules.resolveImportPath(dir, project, path)` resolves relative + `@/`-alias SCSS paths (it is `internal`, accessible from the new package — same Gradle module).
- Declaration PSI tokens (for `findElementAt` targets): variable `$name` (`SCSS_VARIABLE`), function name (`CSS_FUNCTION_TOKEN`), mixin name (`CSS_IDENT` after `@mixin`), placeholder `%name` in a ruleset.
- plugin.xml EP shapes (see `src/main/resources/META-INF/plugin.xml:26-91`): `findUsagesHandlerFactory order="first" implementation=`, `gotoDeclarationHandler implementation=`, and `localInspection language="CSS" shortName= displayName= groupName="CSS" enabledByDefault="true" level="WARNING" implementationClass=` (register ONCE on `language="CSS"` — gotcha #9: per-dialect registration fires 2–3× on a `.scss`).

Run all tests:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test
```
Run one class:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssImportGraphTest"
```

## File Structure

- **Create** `src/main/kotlin/com/webdx/scsssymbols/ScssImportGraph.kt` — parse `@use`/`@import`/`@forward` edges; `provided` / `globalScopeFiles` / `namespaceTargets` closures (cached).
- **Create** `src/main/kotlin/com/webdx/scsssymbols/ScssSymbols.kt` — `Kind`, `Decl`, `Ref`, `declarationsIn`, `referencesIn`, `referenceAt`, `resolve`, `usesByDeclaration`.
- **Create** `src/main/kotlin/com/webdx/scsssymbols/ScssUnusedSymbolInspection.kt`.
- **Create** `src/main/kotlin/com/webdx/scsssymbols/ScssSymbolFindUsagesHandlerFactory.kt`.
- **Create** `src/main/kotlin/com/webdx/scsssymbols/ScssSymbolGotoDeclarationHandler.kt`.
- **Modify** `src/main/resources/META-INF/plugin.xml` — register the three EPs.
- **Create** test classes mirroring each (`ScssImportGraphTest`, `ScssSymbolsTest`, `ScssUnusedSymbolInspectionTest`, `ScssSymbolFindUsagesTest`, `ScssSymbolGotoDeclarationTest`).
- **Modify** `README.md`, `CHANGELOG.md`, `build.gradle.kts` (version → 1.9.0).

---

## Task 1: `ScssImportGraph` — edges + `provided` closure

**Files:**
- Create: `src/main/kotlin/com/webdx/scsssymbols/ScssImportGraph.kt`
- Test: `src/test/kotlin/com/webdx/scsssymbols/ScssImportGraphTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/webdx/scsssymbols/ScssImportGraphTest.kt`:

```kotlin
package com.webdx.scsssymbols

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssImportGraphTest : BasePlatformTestCase() {

    fun testProvidedFollowsForwardAndImportTransitively() {
        val c = myFixture.addFileToProject("c.scss", "\$c: 1;")
        val b = myFixture.addFileToProject("b.scss", "@forward './c.scss';\n\$b: 1;")
        val a = myFixture.addFileToProject("a.scss", "@forward './b.scss';\n\$a: 1;")
        val provided = ScssImportGraph.provided(project, a.virtualFile)
        assertEquals(
            setOf("a.scss", "b.scss", "c.scss"),
            provided.map { it.name }.toSet(),
        )
        // unrelated file not pulled in
        assertFalse(provided.contains(c.virtualFile.parent.findChild("nope.scss")))
    }

    fun testProvidedIsCycleSafe() {
        myFixture.addFileToProject("x.scss", "@forward './y.scss';")
        val y = myFixture.addFileToProject("y.scss", "@forward './x.scss';\n\$y: 1;")
        val provided = ScssImportGraph.provided(project, y.virtualFile)
        assertEquals(setOf("x.scss", "y.scss"), provided.map { it.name }.toSet())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssImportGraphTest"`
Expected: FAIL — `ScssImportGraph` unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/webdx/scsssymbols/ScssImportGraph.kt`:

```kotlin
package com.webdx.scsssymbols

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.webdx.cssmodules.CssModules

/**
 * The project's SCSS import graph and the Sass scope closures derived from it.
 * Parsed by regex over file text (no Sass-plugin PSI dependency); paths resolved via
 * [CssModules.resolveImportPath] plus Sass partial fallbacks. Cached on the project +
 * PSI modification count.
 */
internal object ScssImportGraph {

    private val SCSS_USE = Regex("""@use\s+['"]([^'"]+)['"](?:\s+as\s+([\w*-]+))?""")
    private val SCSS_IMPORT = Regex("""@import\s+([^;{}\n]*)""")
    private val SCSS_FORWARD = Regex("""@forward\s+['"]([^'"]+)['"]""")
    private val QUOTED = Regex("""['"]([^'"]+)['"]""")

    /** Parsed imports of one file. `uses` namespace == null means `@use … as *` (global). */
    data class Edges(
        val uses: List<Pair<VirtualFile, String?>>,
        val imports: List<VirtualFile>,
        val forwards: List<VirtualFile>,
    )

    private fun graph(project: Project): Map<VirtualFile, Edges> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val out = HashMap<VirtualFile, Edges>()
            for (ext in listOf("scss", "sass")) {
                for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                    out[vf] = parseEdges(vf, project)
                }
            }
            CachedValueProvider.Result.create<Map<VirtualFile, Edges>>(
                out, PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    private fun parseEdges(vf: VirtualFile, project: Project): Edges {
        val empty = Edges(emptyList(), emptyList(), emptyList())
        val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: return empty
        val dir = vf.parent ?: return empty
        val uses = ArrayList<Pair<VirtualFile, String?>>()
        for (m in SCSS_USE.findAll(text)) {
            val path = m.groupValues[1]
            if (path.startsWith("sass:")) continue // built-in module (math, color, …)
            val target = resolveScssImport(dir, project, path) ?: continue
            val asName = m.groupValues[2]
            val ns = when {
                asName.isEmpty() -> defaultNamespace(path)
                asName == "*" -> null
                else -> asName
            }
            uses.add(target to ns)
        }
        val imports = ArrayList<VirtualFile>()
        for (m in SCSS_IMPORT.findAll(text)) {
            for (q in QUOTED.findAll(m.groupValues[1])) {
                resolveScssImport(dir, project, q.groupValues[1])?.let { imports.add(it) }
            }
        }
        val forwards = ArrayList<VirtualFile>()
        for (m in SCSS_FORWARD.findAll(text)) {
            resolveScssImport(dir, project, m.groupValues[1])?.let { forwards.add(it) }
        }
        return Edges(uses, imports, forwards)
    }

    /** [file] + transitive closure over its `@forward`/`@import` edges (what `@use`-ing it exposes). Cycle-safe. */
    fun provided(project: Project, file: VirtualFile): Set<VirtualFile> {
        val g = graph(project)
        val out = LinkedHashSet<VirtualFile>()
        fun visit(vf: VirtualFile) {
            if (!out.add(vf)) return
            val e = g[vf] ?: return
            e.forwards.forEach(::visit)
            e.imports.forEach(::visit)
        }
        visit(file)
        return out
    }

    /** Files whose declarations [file] can reference by a BARE name (local + @import + @use-as-*), transitive. */
    fun globalScopeFiles(project: Project, file: VirtualFile): Set<VirtualFile> {
        val g = graph(project)
        val out = LinkedHashSet<VirtualFile>()
        out.add(file)
        val e = g[file] ?: return out
        e.imports.forEach { out.addAll(provided(project, it)) }
        e.uses.filter { it.second == null }.forEach { out.addAll(provided(project, it.first)) }
        return out
    }

    /** `ns -> provided(target)` for each `@use target as ns` in [file]. */
    fun namespaceTargets(project: Project, file: VirtualFile): Map<String, Set<VirtualFile>> {
        val e = graph(project)[file] ?: return emptyMap()
        val out = HashMap<String, Set<VirtualFile>>()
        for ((target, ns) in e.uses) {
            if (ns != null) out[ns] = provided(project, target)
        }
        return out
    }

    private fun defaultNamespace(path: String): String =
        path.substringAfterLast('/').substringBeforeLast('.').removePrefix("_")

    private fun resolveScssImport(dir: VirtualFile, project: Project, path: String): VirtualFile? {
        CssModules.resolveImportPath(dir, project, path)?.let { return it }
        val parent = path.substringBeforeLast('/', "")
        val base = path.substringAfterLast('/')
        for (candidate in listOf("$base.scss", "_$base.scss", "$base.sass", "_$base.sass")) {
            val p = if (parent.isEmpty()) candidate else "$parent/$candidate"
            CssModules.resolveImportPath(dir, project, p)?.let { return it }
        }
        return null
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssImportGraphTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/scsssymbols/ScssImportGraph.kt src/test/kotlin/com/webdx/scsssymbols/ScssImportGraphTest.kt
git commit -m "feat(scsssymbols): SCSS import graph with provided() forward/import closure"
```

---

## Task 2: `ScssImportGraph` — global + namespaced scope

**Files:**
- Modify: `src/main/kotlin/com/webdx/scsssymbols/ScssImportGraph.kt` (methods already added in Task 1)
- Test: `src/test/kotlin/com/webdx/scsssymbols/ScssImportGraphTest.kt`

`globalScopeFiles` and `namespaceTargets` were written in Task 1; this task pins their behavior.

- [ ] **Step 1: Write the failing test**

Add to `ScssImportGraphTest`:

```kotlin
fun testGlobalScopeFromImportAndUseStar() {
    val vars = myFixture.addFileToProject("vars.scss", "\$x: 1;")
    val pal = myFixture.addFileToProject("pal.scss", "\$p: 1;")
    val f = myFixture.addFileToProject(
        "f.scss",
        "@import './vars.scss';\n@use './pal.scss' as *;\n.a { width: \$x; }",
    )
    val scope = ScssImportGraph.globalScopeFiles(project, f.virtualFile).map { it.name }.toSet()
    assertEquals(setOf("f.scss", "vars.scss", "pal.scss"), scope)
}

fun testNamespacedUseIsolatedFromGlobal() {
    val vars = myFixture.addFileToProject("vars.scss", "\$x: 1;")
    val f = myFixture.addFileToProject("f.scss", "@use './vars.scss' as v;\n.a { width: v.\$x; }")
    // not in the bare/global scope …
    assertEquals(setOf("f.scss"), ScssImportGraph.globalScopeFiles(project, f.virtualFile).map { it.name }.toSet())
    // … but reachable under namespace `v`
    assertEquals(setOf("vars.scss"), ScssImportGraph.namespaceTargets(project, f.virtualFile)["v"]!!.map { it.name }.toSet())
}

fun testDefaultNamespaceIsBasename() {
    myFixture.addFileToProject("vars.scss", "\$x: 1;")
    val f = myFixture.addFileToProject("f.scss", "@use './vars.scss';\n.a { width: vars.\$x; }")
    assertEquals(setOf("vars.scss"), ScssImportGraph.namespaceTargets(project, f.virtualFile)["vars"]!!.map { it.name }.toSet())
}
```

- [ ] **Step 2: Run the tests**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssImportGraphTest"`
Expected: PASS (implementation already present from Task 1).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/webdx/scsssymbols/ScssImportGraphTest.kt
git commit -m "test(scsssymbols): global vs namespaced SCSS import scope"
```

---

## Task 3: `ScssSymbols` — declarations

**Files:**
- Create: `src/main/kotlin/com/webdx/scsssymbols/ScssSymbols.kt`
- Test: `src/test/kotlin/com/webdx/scsssymbols/ScssSymbolsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/webdx/scsssymbols/ScssSymbolsTest.kt`:

```kotlin
package com.webdx.scsssymbols

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssSymbolsTest : BasePlatformTestCase() {

    fun testDeclarationsInAllKinds() {
        val scss = myFixture.addFileToProject(
            "vars.scss",
            "\$base: 8px;\n@function calcSize(\$f) { @return \$f; }\n@mixin safe { padding: 0; }\n%card { border: 0; }",
        )
        val decls = ScssSymbols.declarationsIn(scss)
        val byName = decls.associateBy { it.name }
        assertEquals(ScssSymbols.Kind.VARIABLE, byName["base"]!!.kind)
        assertEquals(ScssSymbols.Kind.FUNCTION, byName["calcSize"]!!.kind)
        assertEquals(ScssSymbols.Kind.MIXIN, byName["safe"]!!.kind)
        assertEquals(ScssSymbols.Kind.PLACEHOLDER, byName["card"]!!.kind)
        // the element is the name token at the declaration site
        assertEquals("\$base", byName["base"]!!.element.text)
    }

    fun testDeclarationsIgnoreReferences() {
        val scss = myFixture.addFileToProject(
            "f.scss",
            "\$a: 1;\n.x { width: \$a; @include foo; }",
        )
        // `$a` used in `.x` is NOT a second declaration; `foo` include is not a declaration
        assertEquals(listOf("a"), ScssSymbols.declarationsIn(scss).map { it.name })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssSymbolsTest"`
Expected: FAIL — `ScssSymbols` unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/webdx/scsssymbols/ScssSymbols.kt`:

```kotlin
package com.webdx.scsssymbols

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * SCSS symbol declarations and references, detected by regex over file text (no
 * Sass-plugin PSI dependency); PSI elements are located via `findElementAt` so they can
 * be highlighted / navigated. The [resolve] / [usesByDeclaration] resolution lives in a
 * separate file (ScssSymbolsResolve.kt) on top of [ScssImportGraph].
 */
internal object ScssSymbols {

    enum class Kind { VARIABLE, FUNCTION, MIXIN, PLACEHOLDER }

    /** A declaration: bare [name] (no `$`/`%`), [kind], the name-token [element], and its [file]. */
    data class Decl(val name: String, val kind: Kind, val element: PsiElement, val file: PsiFile)

    private val VAR_DECL = Regex("""(?m)^[ \t]*\$([\w-]+)\s*:""")
    private val FUNC_DECL = Regex("""@function\s+([\w-]+)""")
    private val MIXIN_DECL = Regex("""@mixin\s+([\w-]+)""")
    private val PLACEHOLDER_DECL = Regex("""%([\w-]+)\s*[{,]""")

    /** All symbol declarations in [file]. The name capture group's start is used to anchor the PSI element. */
    fun declarationsIn(file: PsiFile): List<Decl> {
        val text = file.text
        val out = ArrayList<Decl>()
        fun collect(re: Regex, kind: Kind) {
            for (m in re.findAll(text)) {
                val nameRange = m.groups[1]!!.range
                val element = file.findElementAt(nameRange.first) ?: continue
                out.add(Decl(m.groupValues[1], kind, element, file))
            }
        }
        collect(VAR_DECL, Kind.VARIABLE)
        collect(FUNC_DECL, Kind.FUNCTION)
        collect(MIXIN_DECL, Kind.MIXIN)
        collect(PLACEHOLDER_DECL, Kind.PLACEHOLDER)
        return out
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssSymbolsTest"`
Expected: PASS.

> If `byName["base"]!!.element.text` is not exactly `"$base"` (the platform may anchor the leaf differently), adjust the assertion to `assertTrue(byName["base"]!!.element.text.contains("base"))` — the important contract is that the element sits at the declaration's name. Keep the element as whatever `findElementAt(nameStart)` returns.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/scsssymbols/ScssSymbols.kt src/test/kotlin/com/webdx/scsssymbols/ScssSymbolsTest.kt
git commit -m "feat(scsssymbols): declaration detection for var/function/mixin/placeholder"
```

---

## Task 4: `ScssSymbols` — references + `referenceAt`

**Files:**
- Modify: `src/main/kotlin/com/webdx/scsssymbols/ScssSymbols.kt`
- Test: `src/test/kotlin/com/webdx/scsssymbols/ScssSymbolsTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `ScssSymbolsTest`:

```kotlin
fun testReferencesInAllForms() {
    val scss = myFixture.addFileToProject(
        "f.scss",
        "\$a: 1;\n.x {\n  width: \$a;\n  height: v.\$b;\n  margin: calcSize(\$a);\n  @include safe;\n  @include ns.safe2;\n  @extend %card;\n}",
    )
    val refs = ScssSymbols.referencesIn(scss)
    fun has(name: String, kind: ScssSymbols.Kind, ns: String?) =
        refs.any { it.name == name && it.kind == kind && it.namespace == ns }
    assertTrue("bare var", has("a", ScssSymbols.Kind.VARIABLE, null))
    assertTrue("ns var", has("b", ScssSymbols.Kind.VARIABLE, "v"))
    assertTrue("function call", has("calcSize", ScssSymbols.Kind.FUNCTION, null))
    assertTrue("mixin include", has("safe", ScssSymbols.Kind.MIXIN, null))
    assertTrue("ns mixin", has("safe2", ScssSymbols.Kind.MIXIN, "ns"))
    assertTrue("placeholder extend", has("card", ScssSymbols.Kind.PLACEHOLDER, null))
    // the `$a:` declaration LHS is NOT a reference
    assertFalse("decl LHS not a ref", refs.any { it.kind == ScssSymbols.Kind.VARIABLE && it.name == "a" && it.element.textOffset == scss.text.indexOf("\$a: 1") })
}

fun testReferenceAtClassifiesCaret() {
    val scss = myFixture.addFileToProject("f.scss", "\$a: 1;\n.x { width: \$a; }")
    val offset = scss.text.lastIndexOf("\$a") + 1 // inside the usage `$a`
    val ref = ScssSymbols.referenceAt(scss.findElementAt(offset)!!)
    assertEquals("a", ref!!.name)
    assertEquals(ScssSymbols.Kind.VARIABLE, ref.kind)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssSymbolsTest.testReferencesInAllForms" --tests "com.webdx.scsssymbols.ScssSymbolsTest.testReferenceAtClassifiesCaret"`
Expected: FAIL — `referencesIn` / `Ref` / `referenceAt` unresolved.

- [ ] **Step 3: Write the implementation**

Add to `ScssSymbols` (in `ScssSymbols.kt`):

```kotlin
    /** A reference: bare [name], [kind], optional [namespace] (`ns.` prefix), the use [element], and its [file]. */
    data class Ref(val name: String, val kind: Kind, val namespace: String?, val element: PsiElement, val file: PsiFile)

    // ns. prefix is captured in group 1 (optional); the name in group 2.
    private val VAR_REF = Regex("""(?:([\w-]+)\.)?\$([\w-]+)""")
    private val FUNC_REF = Regex("""(?:([\w-]+)\.)?([\w-]+)\s*\(""")
    private val MIXIN_REF = Regex("""@include\s+(?:([\w-]+)\.)?([\w-]+)""")
    private val PLACEHOLDER_REF = Regex("""@extend\s+(?:([\w-]+)\.)?%([\w-]+)""")

    /** All symbol references in [file] (every use site), with namespace prefixes captured. */
    fun referencesIn(file: PsiFile): List<Ref> {
        val text = file.text
        val out = ArrayList<Ref>()
        // declaration LHS `$name:` offsets — excluded from variable references.
        val varDeclStarts = VAR_DECL.findAll(text).map { it.groups[1]!!.range.first }.toSet()

        for (m in VAR_REF.findAll(text)) {
            val nameStart = m.groups[2]!!.range.first
            if (nameStart in varDeclStarts) continue // it's a declaration LHS, not a use
            val element = file.findElementAt(nameStart) ?: continue
            out.add(Ref(m.groupValues[2], Kind.VARIABLE, m.groupValues[1].ifEmpty { null }, element, file))
        }
        for (m in FUNC_REF.findAll(text)) {
            // skip declarations and mixin includes that also match `name(`
            val before = text.substring(maxOf(0, m.range.first - 12), m.range.first)
            if (before.endsWith("@function ") || before.endsWith("@mixin ") || before.endsWith("@include ")) continue
            val nameStart = m.groups[2]!!.range.first
            val element = file.findElementAt(nameStart) ?: continue
            out.add(Ref(m.groupValues[2], Kind.FUNCTION, m.groupValues[1].ifEmpty { null }, element, file))
        }
        for (m in MIXIN_REF.findAll(text)) {
            val nameStart = m.groups[2]!!.range.first
            val element = file.findElementAt(nameStart) ?: continue
            out.add(Ref(m.groupValues[2], Kind.MIXIN, m.groupValues[1].ifEmpty { null }, element, file))
        }
        for (m in PLACEHOLDER_REF.findAll(text)) {
            val nameStart = m.groups[2]!!.range.first
            val element = file.findElementAt(nameStart) ?: continue
            out.add(Ref(m.groupValues[2], Kind.PLACEHOLDER, m.groupValues[1].ifEmpty { null }, element, file))
        }
        return out
    }

    /** The reference whose name-token contains [element]'s offset, or null. */
    fun referenceAt(element: PsiElement): Ref? {
        val file = element.containingFile?.originalFile ?: return null
        val offset = element.textOffset
        return referencesIn(file).firstOrNull { it.element.textRange.contains(offset) }
    }
```

> Note: `FUNC_REF` also matches CSS built-ins (`rgba(`, `calc(`); they are harmless — `resolve` (Task 5) only attributes a reference when a matching *declared* function exists in scope, so non-symbol calls resolve to null and are dropped.

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssSymbolsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/scsssymbols/ScssSymbols.kt src/test/kotlin/com/webdx/scsssymbols/ScssSymbolsTest.kt
git commit -m "feat(scsssymbols): reference detection + referenceAt (namespace-aware)"
```

---

## Task 5: Resolution + `usesByDeclaration`

**Files:**
- Create: `src/main/kotlin/com/webdx/scsssymbols/ScssSymbolsResolve.kt`
- Test: `src/test/kotlin/com/webdx/scsssymbols/ScssSymbolsResolveTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/webdx/scsssymbols/ScssSymbolsResolveTest.kt`:

```kotlin
package com.webdx.scsssymbols

import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssSymbolsResolveTest : BasePlatformTestCase() {

    fun testResolvesNamespacedAndBareAcrossFiles() {
        val vars = myFixture.addFileToProject("vars.scss", "\$x: 1;\n@mixin safe { padding: 0; }")
        val f = myFixture.addFileToProject(
            "f.scss",
            "@use './vars.scss' as v;\n@import './vars.scss';\n.a { width: v.\$x; height: \$x; @include safe; }",
        )
        val refs = ScssSymbols.referencesIn(f)
        val nsVar = refs.first { it.name == "x" && it.namespace == "v" }
        val bareVar = refs.first { it.name == "x" && it.namespace == null }
        val mixin = refs.first { it.kind == ScssSymbols.Kind.MIXIN }
        assertEquals("vars.scss", ScssSymbols.resolve(nsVar)!!.file.name)
        assertEquals("vars.scss", ScssSymbols.resolve(bareVar)!!.file.name)
        assertEquals("vars.scss", ScssSymbols.resolve(mixin)!!.file.name)
    }

    fun testResolvePrefersLocalOnCollision() {
        myFixture.addFileToProject("vars.scss", "\$x: 1;")
        val f = myFixture.addFileToProject("f.scss", "@import './vars.scss';\n\$x: 2;\n.a { width: \$x; }")
        val use = ScssSymbols.referencesIn(f).first { it.name == "x" }
        // local `$x` (in f.scss) wins over the imported one
        assertEquals("f.scss", ScssSymbols.resolve(use)!!.file.name)
    }

    fun testUsesByDeclarationGroupsRefs() {
        val vars = myFixture.addFileToProject("vars.scss", "\$used: 1;\n\$dead: 2;")
        myFixture.addFileToProject("f.scss", "@import './vars.scss';\n.a { width: \$used; height: \$used; }")
        val uses = ScssSymbols.usesByDeclaration(project)
        val usedKey = ScssSymbols.declKey(vars.virtualFile, "used", ScssSymbols.Kind.VARIABLE)
        val deadKey = ScssSymbols.declKey(vars.virtualFile, "dead", ScssSymbols.Kind.VARIABLE)
        assertEquals(2, uses[usedKey]?.size ?: 0)
        assertNull(uses[deadKey])
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssSymbolsResolveTest"`
Expected: FAIL — `resolve` / `usesByDeclaration` / `declKey` unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/webdx/scsssymbols/ScssSymbolsResolve.kt`:

```kotlin
package com.webdx.scsssymbols

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** Stable map key for a declaration (PsiElement isn't a safe key across passes). */
internal data class DeclKey(val file: VirtualFile, val name: String, val kind: ScssSymbols.Kind)

internal fun ScssSymbols.declKey(file: VirtualFile, name: String, kind: ScssSymbols.Kind) =
    DeclKey(file, name, kind)

/** Resolve a reference to the declaration it points at, honoring import scope + namespaces. */
internal fun ScssSymbols.resolve(ref: ScssSymbols.Ref): ScssSymbols.Decl? {
    val project = ref.file.project
    val vf = ref.file.originalFile.virtualFile ?: return null
    val candidates: List<VirtualFile> = if (ref.namespace != null) {
        (ScssImportGraph.namespaceTargets(project, vf)[ref.namespace] ?: emptySet()).toList()
    } else {
        // local file first so a local declaration wins a name collision
        val global = ScssImportGraph.globalScopeFiles(project, vf)
        listOf(vf) + (global - vf)
    }
    val psiManager = PsiManager.getInstance(project)
    for (cand in candidates) {
        val psi = psiManager.findFile(cand) ?: continue
        declarationsIn(psi).firstOrNull { it.name == ref.name && it.kind == ref.kind }?.let { return it }
    }
    return null
}

/** Project-wide map: declaration -> the references that resolve to it. Cached on PSI mod count. */
internal fun ScssSymbols.usesByDeclaration(project: Project): Map<DeclKey, List<ScssSymbols.Ref>> =
    CachedValuesManager.getManager(project).getCachedValue(project) {
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val out = HashMap<DeclKey, MutableList<ScssSymbols.Ref>>()
        for (ext in listOf("scss", "sass")) {
            for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                val psi = psiManager.findFile(vf) ?: continue
                for (ref in referencesIn(psi)) {
                    val decl = resolve(ref) ?: continue
                    val declVf = decl.file.virtualFile ?: continue
                    out.getOrPut(DeclKey(declVf, decl.name, decl.kind)) { mutableListOf() }.add(ref)
                }
            }
        }
        CachedValueProvider.Result.create<Map<DeclKey, List<ScssSymbols.Ref>>>(
            out, PsiModificationTracker.MODIFICATION_COUNT,
        )
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssSymbolsResolveTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/scsssymbols/ScssSymbolsResolve.kt src/test/kotlin/com/webdx/scsssymbols/ScssSymbolsResolveTest.kt
git commit -m "feat(scsssymbols): scope-aware resolve + cached usesByDeclaration"
```

---

## Task 6: Go-to-declaration

**Files:**
- Create: `src/main/kotlin/com/webdx/scsssymbols/ScssSymbolGotoDeclarationHandler.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/webdx/scsssymbols/ScssSymbolGotoDeclarationTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/webdx/scsssymbols/ScssSymbolGotoDeclarationTest.kt`:

```kotlin
package com.webdx.scsssymbols

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssSymbolGotoDeclarationTest : BasePlatformTestCase() {

    private val handler = ScssSymbolGotoDeclarationHandler()

    private fun targetFileAtCaret(): String? {
        val element = myFixture.file.findElementAt(myFixture.caretOffset)
        return handler.getGotoDeclarationTargets(element, myFixture.caretOffset, myFixture.editor)
            ?.firstOrNull()?.containingFile?.name
    }

    fun testGoesToVariableDeclarationAcrossFiles() {
        myFixture.addFileToProject("vars.scss", "\$brand: red;")
        myFixture.configureByText("f.scss", "@use './vars.scss' as v;\n.a { color: v.\$bra<caret>nd; }")
        assertEquals("vars.scss", targetFileAtCaret())
    }

    fun testGoesToMixinDeclaration() {
        myFixture.addFileToProject("mix.scss", "@mixin safe { padding: 0; }")
        myFixture.configureByText("f.scss", "@import './mix.scss';\n.a { @include sa<caret>fe; }")
        assertEquals("mix.scss", targetFileAtCaret())
    }

    fun testNoTargetForUnknownSymbol() {
        myFixture.configureByText("f.scss", ".a { color: \$noth<caret>ing; }")
        assertNull(targetFileAtCaret())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssSymbolGotoDeclarationTest"`
Expected: FAIL — handler unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/webdx/scsssymbols/ScssSymbolGotoDeclarationHandler.kt`:

```kotlin
package com.webdx.scsssymbols

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/**
 * Go-to-declaration on a SCSS symbol use (`$x` / `ns.$x` / `@include name` / `func(` /
 * `@extend %ph`) → the declaration, resolved through the import graph. The standard EP
 * works for `.scss` (the TS-Go fork only intercepts `.tsx`), so no action override.
 */
class ScssSymbolGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        if (element.firstChild != null) return null // leaves only
        val ref = ScssSymbols.referenceAt(element) ?: return null
        val target = ScssSymbols.resolve(ref) ?: return null
        return arrayOf(target.element)
    }
}
```

- [ ] **Step 4: Register in plugin.xml**

In `src/main/resources/META-INF/plugin.xml`, after the existing `gotoDeclarationHandler` (line ~33), add:

```xml
        <gotoDeclarationHandler
            implementation="com.webdx.scsssymbols.ScssSymbolGotoDeclarationHandler"/>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssSymbolGotoDeclarationTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/webdx/scsssymbols/ScssSymbolGotoDeclarationHandler.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/webdx/scsssymbols/ScssSymbolGotoDeclarationTest.kt
git commit -m "feat(scsssymbols): go-to-declaration for SCSS symbols"
```

---

## Task 7: Unused-symbol inspection

**Files:**
- Create: `src/main/kotlin/com/webdx/scsssymbols/ScssUnusedSymbolInspection.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/webdx/scsssymbols/ScssUnusedSymbolInspectionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/webdx/scsssymbols/ScssUnusedSymbolInspectionTest.kt`:

```kotlin
package com.webdx.scsssymbols

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssUnusedSymbolInspectionTest : BasePlatformTestCase() {

    fun testUnusedSymbolsGreyedUsedNot() {
        val vars = myFixture.addFileToProject(
            "vars.scss",
            "\$used: 1;\n\$dead: 2;\n@function deadFn(\$a) { @return \$a; }\n@mixin deadMix { x: 1; }\n%deadPh { y: 1; }",
        )
        myFixture.addFileToProject("f.scss", "@import './vars.scss';\n.a { width: \$used; }")
        myFixture.configureFromExistingVirtualFile(vars.virtualFile)
        myFixture.enableInspections(ScssUnusedSymbolInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue("dead var greyed, got: $descriptions", descriptions.any { it.contains("'dead'") && it.contains("Unused") })
        assertTrue("dead fn greyed, got: $descriptions", descriptions.any { it.contains("'deadFn'") })
        assertTrue("dead mixin greyed, got: $descriptions", descriptions.any { it.contains("'deadMix'") })
        assertTrue("dead placeholder greyed, got: $descriptions", descriptions.any { it.contains("'deadPh'") })
        assertFalse("used var not greyed, got: $descriptions", descriptions.any { it.contains("'used'") })
    }

    fun testNamespacedAndIncludeCountAsUsed() {
        val vars = myFixture.addFileToProject("vars.scss", "\$nv: 1;\n@mixin m { x: 1; }")
        myFixture.addFileToProject("f.scss", "@use './vars.scss' as v;\n.a { width: v.\$nv; @include v.m; }")
        myFixture.configureFromExistingVirtualFile(vars.virtualFile)
        myFixture.enableInspections(ScssUnusedSymbolInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse("namespaced-used not greyed, got: $descriptions", descriptions.any { it.contains("'nv'") || it.contains("'m'") })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssUnusedSymbolInspectionTest"`
Expected: FAIL — inspection unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/webdx/scsssymbols/ScssUnusedSymbolInspection.kt`:

```kotlin
package com.webdx.scsssymbols

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor

/**
 * Greys a SCSS symbol declaration (`$var` / `@function` / `@mixin` / `%placeholder`) that
 * no reference in the project resolves to, through the `@use`/`@import`/`@forward` graph.
 * Registered once on `language="CSS"` (covers SCSS/SASS dialects without duplication).
 */
class ScssUnusedSymbolInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        val name = file.name.lowercase()
        if (!name.endsWith(".scss") && !name.endsWith(".sass")) return PsiElementVisitor.EMPTY_VISITOR

        val uses = ScssSymbols.usesByDeclaration(file.project)
        val vf = file.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val decls = ScssSymbols.declarationsIn(file)

        for (decl in decls) {
            val key = DeclKey(vf, decl.name, decl.kind)
            if (uses[key].isNullOrEmpty()) {
                holder.registerProblem(
                    decl.element,
                    "Unused SCSS ${decl.kind.name.lowercase()} '${decl.name}'",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                )
            }
        }
        return PsiElementVisitor.EMPTY_VISITOR
    }
}
```

> Note: this inspection computes everything up-front in `buildVisitor` (declarations are
> resolved against the cached project map) and registers problems directly, returning an
> empty visitor — the same shape works because `registerProblem` is valid during
> `buildVisitor`. If the platform requires problems to be registered from a visitor, switch
> to iterating elements and matching `decl.element` by identity (as `CssModuleUnusedClassInspection`
> does for bam). Verify via the test; prefer the identity-visitor form if the up-front form
> doesn't highlight.

- [ ] **Step 4: Register in plugin.xml**

In `src/main/resources/META-INF/plugin.xml`, alongside the other `localInspection` entries, add (register ONCE on CSS — gotcha #9):

```xml
        <localInspection language="CSS"
            shortName="ScssUnusedSymbol"
            displayName="Unused SCSS symbol"
            groupName="CSS"
            enabledByDefault="true"
            level="WARNING"
            implementationClass="com.webdx.scsssymbols.ScssUnusedSymbolInspection"/>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssUnusedSymbolInspectionTest"`
Expected: PASS. If the up-front registration form does not highlight, switch to the
identity-visitor form (note above) and re-run.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/webdx/scsssymbols/ScssUnusedSymbolInspection.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/webdx/scsssymbols/ScssUnusedSymbolInspectionTest.kt
git commit -m "feat(scsssymbols): unused SCSS symbol inspection"
```

---

## Task 8: Scoped Find Usages

**Files:**
- Create: `src/main/kotlin/com/webdx/scsssymbols/ScssSymbolFindUsagesHandlerFactory.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/webdx/scsssymbols/ScssSymbolFindUsagesTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/webdx/scsssymbols/ScssSymbolFindUsagesTest.kt`:

```kotlin
package com.webdx.scsssymbols

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScssSymbolFindUsagesTest : BasePlatformTestCase() {

    fun testFindsVariableUsagesAcrossFiles() {
        val vars = myFixture.addFileToProject("vars.scss", "\$brand: red;")
        myFixture.addFileToProject("a.scss", "@use './vars.scss' as v;\n.a { color: v.\$brand; }")
        myFixture.addFileToProject("b.scss", "@import './vars.scss';\n.b { color: \$brand; border-color: \$brand; }")
        // caret on the `$brand` declaration
        val offset = vars.text.indexOf("\$brand") + 1
        val element = vars.findElementAt(offset)!!
        val usages = myFixture.findUsages(element)
        // v.$brand (a.scss) + 2× $brand (b.scss) = 3
        assertEquals("usages: ${usages.map { it.element?.containingFile?.name }}", 3, usages.size)
    }

    fun testDoesNotMatchSameNamedSymbolResolvingElsewhere() {
        val vars = myFixture.addFileToProject("vars.scss", "\$x: 1;")
        // other.scss declares its OWN $x and uses it locally — must NOT count for vars.scss's $x
        myFixture.addFileToProject("other.scss", "\$x: 2;\n.o { width: \$x; }")
        val offset = vars.text.indexOf("\$x") + 1
        val usages = myFixture.findUsages(vars.findElementAt(offset)!!)
        assertEquals("usages: ${usages.map { it.element?.containingFile?.name }}", 0, usages.size)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssSymbolFindUsagesTest"`
Expected: FAIL — factory unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/webdx/scsssymbols/ScssSymbolFindUsagesHandlerFactory.kt`:

```kotlin
package com.webdx.scsssymbols

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

/**
 * Find Usages on a SCSS symbol declaration → every reference the import graph resolves to
 * it, project-wide. Reuses the cached `usesByDeclaration` map; no platform reference search
 * (avoids resolving each candidate through the TS service).
 */
class ScssSymbolFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        val name = file.name.lowercase()
        if (!name.endsWith(".scss") && !name.endsWith(".sass")) return false
        return declAt(element) != null
    }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler {
        return object : FindUsagesHandler(element) {
            override fun getFindUsagesOptions(dataContext: DataContext?): FindUsagesOptions =
                super.getFindUsagesOptions(dataContext)

            override fun processElementUsages(
                target: PsiElement,
                processor: Processor<in UsageInfo>,
                options: FindUsagesOptions,
            ): Boolean = ReadAction.compute<Boolean, RuntimeException> {
                val decl = declAt(target) ?: return@compute true
                val vf = decl.file.virtualFile ?: return@compute true
                val key = DeclKey(vf, decl.name, decl.kind)
                val refs = ScssSymbols.usesByDeclaration(target.project)[key] ?: emptyList()
                for (ref in refs) {
                    if (!processor.process(UsageInfo(ref.element))) return@compute false
                }
                true
            }
        }
    }

    /** The declaration whose name-token contains [element]'s offset, or null. */
    private fun declAt(element: PsiElement): ScssSymbols.Decl? {
        val file = element.containingFile?.originalFile ?: return null
        val offset = element.textOffset
        return ScssSymbols.declarationsIn(file).firstOrNull { it.element.textRange.contains(offset) }
    }
}
```

- [ ] **Step 4: Register in plugin.xml**

In `src/main/resources/META-INF/plugin.xml`, after the other `findUsagesHandlerFactory` entries (line ~31), add:

```xml
        <findUsagesHandlerFactory
            order="first"
            implementation="com.webdx.scsssymbols.ScssSymbolFindUsagesHandlerFactory"/>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.scsssymbols.ScssSymbolFindUsagesTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/webdx/scsssymbols/ScssSymbolFindUsagesHandlerFactory.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/webdx/scsssymbols/ScssSymbolFindUsagesTest.kt
git commit -m "feat(scsssymbols): scoped Find Usages for SCSS symbols"
```

---

## Task 9: Full suite, docs, version bump

**Files:**
- Modify: `README.md`, `CHANGELOG.md`, `build.gradle.kts:9`

- [ ] **Step 1: Run the entire suite**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Bump version**

In `build.gradle.kts` line 9, change `version = "1.8.0"` to `version = "1.9.0"`.

- [ ] **Step 3: Add the CHANGELOG entry**

At the top of `CHANGELOG.md` (above `## 1.8.0`):

```markdown
## 1.9.0 — 2026-06-15
- **New: SCSS symbol usage** (`com.webdx.scsssymbols`). Project-wide support for `$variables`,
  `@function`s, `@mixin`s, and `%placeholder`s, resolved through the real
  `@use`/`@import`/`@forward` import graph (full transitivity, namespace-aware):
  - **"Unused SCSS symbol" inspection** greys a declaration that no reference anywhere resolves
    to. Precise across name collisions (a same-named symbol used elsewhere does not unflag a
    genuinely-dead one).
  - **Find Usages** on a declaration lists every resolved reference site project-wide.
  - **Go-to-declaration** from a use (`$x` / `ns.$x` / `@include name` / `func(` / `%ph`) lands on
    the declaration in the right file.
  Resolved from source via regex + `findElementAt` (no Sass-plugin PSI dependency); the import
  graph reuses `CssModules.resolveImportPath`. Out of scope (approximated, never a false
  "unused"): `@forward show/hide`/prefix filters, `@use … with`, `sass:*` modules, CSS custom
  properties, parameters, rename. (`ScssImportGraph`, `ScssSymbols`, `ScssUnusedSymbolInspection`,
  `ScssSymbolFindUsagesHandlerFactory`, `ScssSymbolGotoDeclarationHandler`.)
```

- [ ] **Step 4: Add the README section**

In `README.md`, add a new feature section after the dead-exports section (before "## Architecture"):

```markdown
### SCSS symbol usage (`com.webdx.scsssymbols`)

19. **"Unused SCSS symbol" inspection, Find Usages, and go-to** for `$variables`, `@function`s,
    `@mixin`s, and `%placeholder`s, resolved through the project's `@use`/`@import`/`@forward`
    graph (full transitivity, namespace-aware — `ns.$x`, `@include ns.m`, `#{$x}` all count). A
    declaration referenced nowhere is greyed; Find Usages on it lists every resolved reference;
    Cmd+Click a use jumps to its declaration. Resolution is precise across name collisions (a
    same-named symbol used elsewhere does not keep a dead one alive). Source-resolved (regex +
    `findElementAt`, no Sass-plugin dependency); graph reuses `CssModules.resolveImportPath`.
    → `ScssImportGraph`, `ScssSymbols`, `ScssUnusedSymbolInspection`,
    `ScssSymbolFindUsagesHandlerFactory`, `ScssSymbolGotoDeclarationHandler`
```

Also add to the "Extension points used" table a row:
```
| SCSS unused symbol / Find Usages / go-to | `localInspection` (CSS) + `findUsagesHandlerFactory` + `gotoDeclarationHandler` | `com.intellij` |
```

- [ ] **Step 5: Build the plugin**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew clean buildPlugin`
Expected: BUILD SUCCESSFUL; `build/distributions/webdx-1.9.0.zip` produced.

- [ ] **Step 6: Commit**

```bash
git add README.md CHANGELOG.md build.gradle.kts
git commit -m "docs(scsssymbols): document SCSS symbol usage; bump to 1.9.0"
```

---

## Self-Review notes

- **Spec coverage:** import graph + transitivity (Tasks 1–2) ✓; declarations (3) ✓; references + referenceAt (4) ✓; scope-aware resolve + usesByDeclaration (5) ✓; go-to (6) ✓; unused inspection (7) ✓; Find Usages (8) ✓; docs/version (9) ✓. All four symbol kinds covered in each.
- **Type consistency:** `ScssSymbols.Kind`, `Decl(name, kind, element, file)`, `Ref(name, kind, namespace, element, file)`, `DeclKey(file, name, kind)`, `resolve(Ref): Decl?`, `usesByDeclaration(project): Map<DeclKey, List<Ref>>`, `declarationsIn`/`referencesIn`/`referenceAt` — used consistently across Tasks 5–8. `resolve`/`usesByDeclaration`/`declKey` are extension functions on `ScssSymbols` in `ScssSymbolsResolve.kt`; they are called as `ScssSymbols.resolve(...)` etc. (member-style on the object), which works for extension functions whose receiver is the object.
- **Known risks (handled by TDD + review loop):** (a) the unused inspection's up-front registration form may need the identity-visitor fallback (noted in Task 7); (b) `findElementAt(nameStart)` element text may differ slightly from the raw `$name` (noted in Task 3); (c) `FUNC_REF` over-matches CSS built-ins but they resolve to null and are dropped (noted in Task 4). Each has an explicit fallback in its task.
- **No Sass-plugin dependency:** all detection is regex + base PSI (`findElementAt`, `textRange`); `CssModules.resolveImportPath` is the only cross-package call.
```
