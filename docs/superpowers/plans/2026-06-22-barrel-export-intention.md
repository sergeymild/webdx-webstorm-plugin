# Barrel-Export Intention — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Alt+Enter intention on an exported component name that writes `export … from` re-exports into every existing `index.ts(x)` up the directory tree, stopping at the auto-detected module boundary.

**Architecture:** A new package `com.webdx.barrels` with a pure/PSI logic `object BarrelExports` (boundary detection, barrel-chain walk, path + form computation, dedup) and a thin `BarrelExportIntention : PsiElementBaseIntentionAction` that delegates to it and applies edits in one `WriteCommandAction`. All resolution is from source PSI/VFS — no TS service. Reuses `com.webdx.cssmodules.CssModules` for tsconfig alias resolution.

**Tech Stack:** Kotlin 2.3.0, IntelliJ Platform Gradle Plugin 2.6.0, JDK 21, JUnit 4.13.2, `BasePlatformTestCase` against the locally-installed WebStorm SDK.

## Global Constraints

- Plugin id stays `com.webdx.css-modules-scoped-usages`; display name **WebDX**. (verbatim from repo)
- All features resolve from source files on disk — must work under the **TypeScript-Go (tsgo)** engine (no TS-service dependency).
- `<extensions defaultExtensionNs="com.intellij">` — never `defaultExtensionPointName` (gotcha #1).
- PSI reads inside read-action contexts; navigation/resolution against `containingFile.originalFile` (gotcha #8).
- Tests: real IntelliJ engine, `BasePlatformTestCase` fixtures, no mocks. Run with
  `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test`.
- No new bundled test plugins: `JavaScript` (+ `com.intellij.modules.json`) are already declared in `build.gradle.kts`. `package.json` / `tsconfig.json` fixtures are plain files.
- The whole feature must add no false edits: when in doubt, the intention is simply not offered (never break code).

---

### Task 1: Scaffold package, logic stub, and intention registration

Establishes a compiling skeleton so later tasks are pure TDD increments. The intention is registered but always-unavailable until Task 7.

**Files:**
- Create: `src/main/kotlin/com/webdx/barrels/BarrelExports.kt`
- Create: `src/main/kotlin/com/webdx/barrels/BarrelExportIntention.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (add one `<intentionAction>`)

**Interfaces:**
- Produces: `object BarrelExports` with data classes `Style(quote: Char, semi: Boolean, prefersStar: Boolean)`, `BarrelEdit(indexFile: VirtualFile, line: String)`, `Plan(moduleRootLabel: String, edits: List<BarrelEdit>)`.
- Produces: `class BarrelExportIntention : PsiElementBaseIntentionAction`.

- [ ] **Step 1: Create `BarrelExports.kt` with data shapes and stubs**

```kotlin
package com.webdx.barrels

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Source-resolved logic for the "export through barrel modules" intention. Detects the
 * module boundary, walks the chain of existing index.ts(x) barrels from a component up to
 * that boundary, and computes the re-export line each barrel needs. No TS service.
 */
object BarrelExports {

    /** Detected re-export style of a target index file. */
    data class Style(val quote: Char, val semi: Boolean, val prefersStar: Boolean)

    /** One planned append: [line] to add at the end of [indexFile]. */
    data class BarrelEdit(val indexFile: VirtualFile, val line: String)

    /** A full plan: where it stops ([moduleRootLabel], for UI) and what to write. */
    data class Plan(val moduleRootLabel: String, val edits: List<BarrelEdit>)

    private val INDEX_NAMES = listOf("index.ts", "index.tsx", "index.js", "index.jsx")

    // Implemented in later tasks.
    fun indexFileIn(dir: VirtualFile): VirtualFile? = TODO("Task 3")
    fun sourceRoot(fromDir: VirtualFile, project: Project): VirtualFile? = TODO("Task 3")
    fun isModuleRoot(dir: VirtualFile, project: Project): Boolean = TODO("Task 3")
    fun barrelChain(componentDir: VirtualFile, project: Project): List<VirtualFile> = TODO("Task 4")
    fun relativeSpecifier(fromDir: VirtualFile, target: VirtualFile): String = TODO("Task 4")
    fun detectStyle(text: String): Style = TODO("Task 2")
    fun reExportLine(name: String, defaultAs: Boolean, specifier: String, style: Style): String = TODO("Task 2")
    fun forwardsName(text: String, name: String, specifier: String): Boolean = TODO("Task 2")
    fun forwardsDefaultFrom(text: String, specifier: String): Boolean = TODO("Task 2")
    fun exportedNameAt(element: PsiElement): Pair<String, Boolean>? = TODO("Task 5")
    fun planFor(componentFile: PsiFile, name: String, isDefault: Boolean, project: Project): Plan? = TODO("Task 6")
}
```

- [ ] **Step 2: Create `BarrelExportIntention.kt` (inert stub)**

```kotlin
package com.webdx.barrels

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

class BarrelExportIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName(): String = "Export through barrel modules"
    override fun getText(): String = "Export through barrel modules"
    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean = false
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {}
}
```

- [ ] **Step 3: Register the intention in `plugin.xml`**

After the existing `<intentionAction>` block (the CSS one, around line 39-42), add:

```xml
        <intentionAction>
            <className>com.webdx.barrels.BarrelExportIntention</className>
            <category>JavaScript</category>
        </intentionAction>
```

- [ ] **Step 4: Verify it compiles and the suite is still green**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL (the `TODO()` bodies compile; nothing calls them yet).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/barrels/ src/main/resources/META-INF/plugin.xml
git commit -m "feat(barrels): scaffold package, logic stub, intention registration"
```

---

### Task 2: Pure text helpers — style detection, line composition, dedup probes

These operate only on strings, so they are fast pure-logic tests.

**Files:**
- Modify: `src/main/kotlin/com/webdx/barrels/BarrelExports.kt` (implement `detectStyle`, `reExportLine`, `forwardsName`, `forwardsDefaultFrom`)
- Test: `src/test/kotlin/com/webdx/barrels/BarrelExportsTextTest.kt`

**Interfaces:**
- Consumes: `BarrelExports.Style`.
- Produces: `detectStyle(text): Style`, `reExportLine(name, defaultAs, specifier, style): String`, `forwardsName(text, name, specifier): Boolean`, `forwardsDefaultFrom(text, specifier): Boolean`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.webdx.barrels

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.webdx.barrels.BarrelExports.Style

class BarrelExportsTextTest : BasePlatformTestCase() {

    fun testDetectStyleStarNoSemi() {
        val s = BarrelExports.detectStyle("export * from './A'\nexport * from './B'\n")
        assertEquals('\'', s.quote)
        assertFalse(s.semi)
        assertTrue(s.prefersStar)
    }

    fun testDetectStyleNamedWithSemiDoubleQuote() {
        val s = BarrelExports.detectStyle("export { A } from \"./A\";\nexport { B } from \"./B\";\n")
        assertEquals('"', s.quote)
        assertTrue(s.semi)
        assertFalse(s.prefersStar)
    }

    fun testReExportLineNamedStar() {
        val s = Style(quote = '\'', semi = false, prefersStar = true)
        assertEquals("export * from './Button'", BarrelExports.reExportLine("Button", false, "./Button", s))
    }

    fun testReExportLineNamedExplicit() {
        val s = Style(quote = '\'', semi = true, prefersStar = false)
        assertEquals("export { Button } from './Button';", BarrelExports.reExportLine("Button", false, "./Button", s))
    }

    fun testReExportLineDefaultAsAlwaysNamedFormEvenWhenStarPreferred() {
        val s = Style(quote = '\'', semi = false, prefersStar = true)
        assertEquals(
            "export { default as Touchpoint } from './Touchpoint'",
            BarrelExports.reExportLine("Touchpoint", true, "./Touchpoint", s),
        )
    }

    fun testForwardsNameNamed() {
        assertTrue(BarrelExports.forwardsName("export { Button } from './Button'", "Button", "./Button"))
        assertTrue(BarrelExports.forwardsName("export { Inner as Button } from './x'", "Button", "./x"))
        assertFalse(BarrelExports.forwardsName("export { Other } from './x'", "Button", "./x"))
    }

    fun testForwardsNameViaStarFromSameSpecifier() {
        assertTrue(BarrelExports.forwardsName("export * from './Button'", "Button", "./Button"))
        assertFalse(BarrelExports.forwardsName("export * from './Other'", "Button", "./Button"))
    }

    fun testForwardsDefaultFrom() {
        assertTrue(BarrelExports.forwardsDefaultFrom("export { default } from './Touchpoint'", "./Touchpoint"))
        assertFalse(BarrelExports.forwardsDefaultFrom("export { default } from './Other'", "./Touchpoint"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.barrels.BarrelExportsTextTest"`
Expected: FAIL — `TODO()` throws `NotImplementedError`.

- [ ] **Step 3: Implement the helpers**

Replace the four stubs in `BarrelExports.kt`:

```kotlin
    fun detectStyle(text: String): Style {
        val single = text.count { it == '\'' }
        val dbl = text.count { it == '"' }
        val quote = if (dbl > single) '"' else '\''
        val semi = Regex("""from\s+['"][^'"]+['"]\s*;""").containsMatchIn(text)
        val star = Regex("""export\s+\*\s+from""").findAll(text).count()
        val named = Regex("""export\s*(?:type\s+)?\{""").findAll(text).count()
        val prefersStar = star > 0 && star >= named
        return Style(quote, semi, prefersStar)
    }

    fun reExportLine(name: String, defaultAs: Boolean, specifier: String, style: Style): String {
        val q = style.quote
        val body = when {
            defaultAs -> "export { default as $name } from $q$specifier$q"
            style.prefersStar -> "export * from $q$specifier$q"
            else -> "export { $name } from $q$specifier$q"
        }
        return if (style.semi) "$body;" else body
    }

    /**
     * Does [text] already re-export [name] reachably by that name — as a named specifier
     * (`export { name }` / `export { x as name }`) or via `export * from '<specifier>'`
     * (a star carries the named symbols of that child)? Approximation: a `name as y`
     * source-only specifier may over-match; at worst a level is skipped, never wrongly written.
     */
    fun forwardsName(text: String, name: String, specifier: String): Boolean {
        val n = Regex.escape(name)
        val named = Regex("""export\s*(?:type\s+)?\{[^}]*\b$n\b[^}]*}\s*from""")
        if (named.containsMatchIn(text)) return true
        val star = Regex("""export\s+\*\s+from\s+['"]${Regex.escape(specifier)}['"]""")
        return star.containsMatchIn(text)
    }

    /** Does [text] forward the default of [specifier] as default (`export { default } from '<spec>'`)? */
    fun forwardsDefaultFrom(text: String, specifier: String): Boolean =
        Regex("""export\s*\{\s*default\s*}\s*from\s+['"]${Regex.escape(specifier)}['"]""").containsMatchIn(text)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.barrels.BarrelExportsTextTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/barrels/BarrelExports.kt src/test/kotlin/com/webdx/barrels/BarrelExportsTextTest.kt
git commit -m "feat(barrels): style detection, re-export line composition, dedup probes"
```

---

### Task 3: VFS helpers — index lookup, source-root, module-root detection

**Files:**
- Modify: `src/main/kotlin/com/webdx/barrels/BarrelExports.kt` (implement `indexFileIn`, `sourceRoot`, `isModuleRoot`)
- Test: `src/test/kotlin/com/webdx/barrels/BarrelExportsPathTest.kt`

**Interfaces:**
- Consumes: `com.webdx.cssmodules.CssModules.tsconfigAliases(text)` → `CssModules.TsconfigAliases(baseUrl: String?, paths: Map<String,String>)`.
- Produces: `indexFileIn(dir): VirtualFile?`, `sourceRoot(fromDir, project): VirtualFile?`, `isModuleRoot(dir, project): Boolean`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.webdx.barrels

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BarrelExportsPathTest : BasePlatformTestCase() {

    fun testIndexFilePrefersTs() {
        myFixture.addFileToProject("comp/index.tsx", "")
        val dir = myFixture.addFileToProject("comp/a.ts", "").virtualFile.parent
        assertEquals("index.tsx", BarrelExports.indexFileIn(dir)?.name)
    }

    fun testIndexFileNullWhenAbsent() {
        val dir = myFixture.addFileToProject("comp/a.ts", "").virtualFile.parent
        assertNull(BarrelExports.indexFileIn(dir))
    }

    fun testSourceRootFromTsconfigBaseUrlRoot() {
        myFixture.addFileToProject("tsconfig.json", """{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
        val dir = myFixture.addFileToProject("src/feature/a.ts", "").virtualFile.parent
        // baseUrl unset -> tsconfig dir (project root). Its name is the temp root dir.
        val root = BarrelExports.sourceRoot(dir, project)
        assertNotNull(root)
        assertNotNull(root!!.findChild("tsconfig.json"))
    }

    fun testIsModuleRootByPackageJson() {
        myFixture.addFileToProject("packages/ui/package.json", """{ "name": "ui" }""")
        val dir = myFixture.addFileToProject("packages/ui/a.ts", "").virtualFile.parent
        assertTrue(BarrelExports.isModuleRoot(dir, project))
    }

    fun testIsModuleRootByAliasTarget() {
        myFixture.addFileToProject(
            "tsconfig.json",
            """{ "compilerOptions": { "paths": { "@mod": ["./src/mod/index.ts"] } } }""",
        )
        myFixture.addFileToProject("src/mod/index.ts", "")
        val dir = myFixture.addFileToProject("src/mod/a.ts", "").virtualFile.parent
        assertTrue(BarrelExports.isModuleRoot(dir, project))
    }

    fun testIsModuleRootFalseForPlainDir() {
        val dir = myFixture.addFileToProject("src/plain/a.ts", "").virtualFile.parent
        assertFalse(BarrelExports.isModuleRoot(dir, project))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.barrels.BarrelExportsPathTest"`
Expected: FAIL — `TODO()`.

- [ ] **Step 3: Implement the helpers**

Add imports at the top of `BarrelExports.kt`:

```kotlin
import com.intellij.openapi.vfs.VfsUtilCore
import com.webdx.cssmodules.CssModules
```

Replace the three stubs:

```kotlin
    fun indexFileIn(dir: VirtualFile): VirtualFile? =
        INDEX_NAMES.firstNotNullOfOrNull { dir.findChild(it) }

    /**
     * The directory the `@/` / baseUrl alias resolves to (the source root). Found by walking up
     * for a tsconfig.json and applying its baseUrl; falls back to the nearest package.json dir,
     * else null. Used only as the hard ceiling for the upward walk.
     */
    fun sourceRoot(fromDir: VirtualFile, project: Project): VirtualFile? {
        val tsconfig = findUp(fromDir, "tsconfig.json")
        if (tsconfig != null) {
            val text = runCatching { VfsUtilCore.loadText(tsconfig) }.getOrNull()
            val tsDir = tsconfig.parent
            if (text != null && tsDir != null) {
                val baseUrl = CssModules.tsconfigAliases(text).baseUrl
                return if (baseUrl != null) resolveDir(tsDir, baseUrl) ?: tsDir else tsDir
            }
        }
        return findUp(fromDir, "package.json")?.parent
    }

    /** A directory is a module root if it is a workspace package or its index is a path-alias target. */
    fun isModuleRoot(dir: VirtualFile, project: Project): Boolean {
        if (dir.findChild("package.json") != null) return true
        val tsconfig = findUp(dir, "tsconfig.json") ?: return false
        val text = runCatching { VfsUtilCore.loadText(tsconfig) }.getOrNull() ?: return false
        val tsDir = tsconfig.parent ?: return false
        val cfg = CssModules.tsconfigAliases(text)
        val baseDir = cfg.baseUrl?.let { resolveDir(tsDir, it) } ?: tsDir
        val index = indexFileIn(dir)
        return cfg.paths.values.any { template ->
            val target = resolveDir(baseDir, template.substringBefore("*").trimEnd('/'))
            target == dir || (index != null && resolveFile(baseDir, template) == index)
        }
    }

    /** Walk up from [start] (inclusive) looking for a direct child named [name]. */
    private fun findUp(start: VirtualFile, name: String): VirtualFile? {
        var cur: VirtualFile? = start
        while (cur != null) {
            cur.findChild(name)?.let { return it }
            cur = cur.parent
        }
        return null
    }

    /** Resolve a `/`-separated relative path to a directory (ignores trailing file segment if missing). */
    private fun resolveDir(from: VirtualFile, path: String): VirtualFile? = resolveFile(from, path)

    private fun resolveFile(from: VirtualFile, path: String): VirtualFile? {
        var cur: VirtualFile? = from
        for (part in path.split('/')) {
            cur = when (part) {
                "", ".", "*" -> cur
                ".." -> cur?.parent
                else -> cur?.findChild(part)
            }
            if (cur == null) return null
        }
        return cur
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.barrels.BarrelExportsPathTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/barrels/BarrelExports.kt src/test/kotlin/com/webdx/barrels/BarrelExportsPathTest.kt
git commit -m "feat(barrels): index lookup, source-root cap, module-root detection"
```

---

### Task 4: Barrel chain walk + relative specifier

**Files:**
- Modify: `src/main/kotlin/com/webdx/barrels/BarrelExports.kt` (implement `barrelChain`, `relativeSpecifier`)
- Test: `src/test/kotlin/com/webdx/barrels/BarrelExportsChainTest.kt`

**Interfaces:**
- Consumes: `indexFileIn`, `isModuleRoot`, `sourceRoot` (Task 3).
- Produces: `barrelChain(componentDir, project): List<VirtualFile>` (barrel **dirs**, bottom-up, each has an index, ending at the module root); `relativeSpecifier(fromDir, target): String`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.webdx.barrels

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BarrelExportsChainTest : BasePlatformTestCase() {

    private fun tsconfig(body: String) = myFixture.addFileToProject("tsconfig.json", body)

    fun testChainIntchEveryLevelStopsAtHighestIndex() {
        tsconfig("""{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './Button'\n")
        myFixture.addFileToProject("components/Button/index.ts", "export * from './Button'\n")
        val dir = myFixture.addFileToProject("components/Button/Button.tsx", "export const Button = 1\n").virtualFile.parent
        val chain = BarrelExports.barrelChain(dir, project).map { it.name }
        assertEquals(listOf("Button", "components"), chain)
    }

    fun testChainMuseSingleModuleBarrelViaAlias() {
        tsconfig("""{ "compilerOptions": { "baseUrl": ".", "paths": { "@m/score": ["./src/screens/score/index.ts"] } } }""")
        myFixture.addFileToProject("package.json", """{ "name": "rn" }""")
        myFixture.addFileToProject("src/screens/score/index.ts", "export { A } from './A'\n")
        val dir = myFixture.addFileToProject(
            "src/screens/score/components/modals/Sheet.tsx",
            "export const Sheet = 1\n",
        ).virtualFile.parent
        val chain = BarrelExports.barrelChain(dir, project).map { it.name }
        assertEquals(listOf("score"), chain)
    }

    fun testRelativeSpecifierSameDirFileStripsExtension() {
        val file = myFixture.addFileToProject("components/Button/Button.tsx", "").virtualFile
        val dir = file.parent
        assertEquals("./Button", BarrelExports.relativeSpecifier(dir, file))
    }

    fun testRelativeSpecifierMultiSegmentToFile() {
        myFixture.addFileToProject("m/index.ts", "")
        val file = myFixture.addFileToProject("m/components/modals/Sheet.tsx", "").virtualFile
        val dir = file.parent.parent.parent // "m"
        assertEquals("./components/modals/Sheet", BarrelExports.relativeSpecifier(dir, file))
    }

    fun testRelativeSpecifierToChildDir() {
        myFixture.addFileToProject("containers/index.ts", "")
        val child = myFixture.addFileToProject("containers/Touchpoint/index.ts", "").virtualFile.parent
        val dir = child.parent // "containers"
        assertEquals("./Touchpoint", BarrelExports.relativeSpecifier(dir, child))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.barrels.BarrelExportsChainTest"`
Expected: FAIL — `TODO()`.

- [ ] **Step 3: Implement the helpers**

Replace the two stubs:

```kotlin
    /**
     * Existing index-barrel directories from [componentDir] up to (and including) the module root,
     * bottom-up. Directories without an index are skipped. The walk stops at the first module root
     * (package.json / alias-target) or at the source-root cap, whichever comes first.
     */
    fun barrelChain(componentDir: VirtualFile, project: Project): List<VirtualFile> {
        val cap = sourceRoot(componentDir, project)
        val out = mutableListOf<VirtualFile>()
        var d: VirtualFile? = componentDir
        while (d != null) {
            if (indexFileIn(d) != null) out += d
            if (isModuleRoot(d, project)) break
            if (d == cap) break
            d = d.parent
        }
        return out
    }

    /** Relative `./…` specifier from [fromDir] to [target] (an ancestor of target), file extension dropped. */
    fun relativeSpecifier(fromDir: VirtualFile, target: VirtualFile): String {
        val rel = VfsUtilCore.getRelativePath(target, fromDir) ?: target.name
        val noExt = if (target.isDirectory) rel else {
            val ext = target.extension
            if (ext != null) rel.removeSuffix(".$ext") else rel
        }
        return "./$noExt"
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.barrels.BarrelExportsChainTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/barrels/BarrelExports.kt src/test/kotlin/com/webdx/barrels/BarrelExportsChainTest.kt
git commit -m "feat(barrels): barrel-chain walk and relative specifier"
```

---

### Task 5: Exported symbol under the caret

**Files:**
- Modify: `src/main/kotlin/com/webdx/barrels/BarrelExports.kt` (implement `exportedNameAt`)
- Test: `src/test/kotlin/com/webdx/barrels/BarrelExportsSymbolTest.kt`

**Interfaces:**
- Consumes: `com.intellij.lang.ecmascript6.resolve.ES6ImportHandler.isExportedDirectly`, `com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment`, `com.intellij.lang.ecmascript6.psi.ES6ExportSpecifier`, `com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration`, `com.intellij.lang.javascript.psi.JSPsiNamedElementBase`.
- Produces: `exportedNameAt(element): Pair<String, Boolean>?` — `(name, isDefault)` or null when the caret is not on an exported top-level symbol.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.webdx.barrels

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BarrelExportsSymbolTest : BasePlatformTestCase() {

    private fun nameAt(filename: String, text: String): Pair<String, Boolean>? {
        myFixture.configureByText(filename, text)
        val el = myFixture.file.findElementAt(myFixture.caretOffset)!!
        return BarrelExports.exportedNameAt(el)
    }

    fun testNamedConst() {
        assertEquals("Button" to false, nameAt("Button.tsx", "export const Butt<caret>on = () => null\n"))
    }

    fun testNamedFunction() {
        assertEquals("Foo" to false, nameAt("Foo.ts", "export function F<caret>oo() {}\n"))
    }

    fun testDefaultFunction() {
        assertEquals("Avatar" to true, nameAt("Avatar.tsx", "export default function Ava<caret>tar() {}\n"))
    }

    fun testLocalExportSpecifier() {
        assertEquals("Bar" to false, nameAt("x.ts", "const Bar = 1\nexport { B<caret>ar }\n"))
    }

    fun testNonExportedReturnsNull() {
        assertNull(nameAt("x.ts", "const Pri<caret>vate = 1\n"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.barrels.BarrelExportsSymbolTest"`
Expected: FAIL — `TODO()`.

- [ ] **Step 3: Implement `exportedNameAt`**

Add imports:

```kotlin
import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ExportSpecifier
import com.intellij.lang.ecmascript6.resolve.ES6ImportHandler
import com.intellij.lang.javascript.psi.JSPsiNamedElementBase
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
```

Replace the stub:

```kotlin
    /**
     * The exported top-level symbol the caret sits on: `(name, isDefault)`, or null when the caret is
     * not on an exported declaration / specifier. Anonymous `export default` derives the name from the
     * file basename. Resolves against the original (physical) file.
     */
    fun exportedNameAt(element: PsiElement): Pair<String, Boolean>? {
        var el: PsiElement? = element
        var depth = 0
        while (el != null && el !is PsiFile && depth < 20) {
            when (val cur = el) {
                is ES6ExportDefaultAssignment -> {
                    val named = cur.namedElement as? JSPsiNamedElementBase
                    val name = named?.name
                        ?: element.containingFile?.originalFile?.virtualFile?.nameWithoutExtension
                    return name?.let { it to true }
                }
                is JSPsiNamedElementBase -> {
                    if (ES6ImportHandler.isExportedDirectly(cur)) {
                        val isDefault = PsiTreeUtil.getParentOfType(cur, ES6ExportDefaultAssignment::class.java, false) != null
                        cur.name?.let { return it to isDefault }
                    }
                }
            }
            el = el.parent
            depth++
        }
        val spec = PsiTreeUtil.getParentOfType(element, ES6ExportSpecifier::class.java, false)
        if (spec != null) {
            val decl = PsiTreeUtil.getParentOfType(spec, ES6ExportDeclaration::class.java, false)
            if (decl != null && decl.fromClause == null) {
                spec.declaredName?.let { return it to (it == "default") }
            }
        }
        return null
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.barrels.BarrelExportsSymbolTest"`
Expected: PASS (5 tests). If `ES6ExportDefaultAssignment.namedElement` is named differently in this SDK, adjust to `getNamedElement()` (verified shape used by `DeadExportInspection`).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/barrels/BarrelExports.kt src/test/kotlin/com/webdx/barrels/BarrelExportsSymbolTest.kt
git commit -m "feat(barrels): detect exported symbol under the caret"
```

---

### Task 6: `planFor` orchestration

**Files:**
- Modify: `src/main/kotlin/com/webdx/barrels/BarrelExports.kt` (implement `planFor`)
- Test: `src/test/kotlin/com/webdx/barrels/BarrelExportsPlanTest.kt`

**Interfaces:**
- Consumes: every helper above.
- Produces: `planFor(componentFile, name, isDefault, project): Plan?` — null when no barrel chain or nothing to add; otherwise edits bottom-up with each level's exact line, tracking the default→named conversion point.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.webdx.barrels

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BarrelExportsPlanTest : BasePlatformTestCase() {

    private fun tsconfig(body: String) = myFixture.addFileToProject("tsconfig.json", body)
    private fun lines(p: BarrelExports.Plan?) =
        p?.edits?.map { it.indexFile.parent.name to it.line }

    fun testIntchNamedStarStyle() {
        tsconfig("""{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './Other'\n")
        myFixture.addFileToProject("components/Button/index.ts", "export * from './nothing-yet'\n")
        val file = myFixture.addFileToProject("components/Button/Button.tsx", "export const Button = 1\n")
        val plan = BarrelExports.planFor(file, "Button", false, project)
        assertEquals(
            listOf(
                "Button" to "export * from './Button'",
                "components" to "export * from './Button'",
            ),
            lines(plan),
        )
        assertEquals("components", plan!!.moduleRootLabel)
    }

    fun testTouchpointDefaultExportConversion() {
        tsconfig("""{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("src/containers/index.ts", "export * from './Basic'\n")
        myFixture.addFileToProject(
            "src/containers/Touchpoint/index.ts",
            "export { default } from './Touchpoint'\n",
        )
        val file = myFixture.addFileToProject(
            "src/containers/Touchpoint/Touchpoint.tsx",
            "export default function Touchpoint() {}\n",
        )
        val plan = BarrelExports.planFor(file, "Touchpoint", true, project)
        // Leaf already forwards default-as-default -> skipped; parent converts default->named.
        assertEquals(
            listOf("containers" to "export { default as Touchpoint } from './Touchpoint'"),
            lines(plan),
        )
    }

    fun testMuseNamedStyleMultiSegmentWithSemicolons() {
        tsconfig("""{ "compilerOptions": { "baseUrl": ".", "paths": { "@m/score": ["./src/screens/score/index.ts"] } } }""")
        myFixture.addFileToProject("package.json", """{ "name": "rn" }""")
        myFixture.addFileToProject("src/screens/score/index.ts", "export { A } from './A';\n")
        val file = myFixture.addFileToProject(
            "src/screens/score/components/modals/Sheet.tsx",
            "export const Sheet = 1\n",
        )
        val plan = BarrelExports.planFor(file, "Sheet", false, project)
        assertEquals(
            listOf("score" to "export { Sheet } from './components/modals/Sheet';"),
            lines(plan),
        )
    }

    fun testNullWhenAlreadyFullyWired() {
        tsconfig("""{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './Button'\n")
        myFixture.addFileToProject("components/Button/index.ts", "export * from './Button'\n")
        val file = myFixture.addFileToProject("components/Button/Button.tsx", "export const Button = 1\n")
        assertNull(BarrelExports.planFor(file, "Button", false, project))
    }

    fun testNullWhenNoBarrelAncestor() {
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        val file = myFixture.addFileToProject("loose/Button.tsx", "export const Button = 1\n")
        assertNull(BarrelExports.planFor(file, "Button", false, project))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.barrels.BarrelExportsPlanTest"`
Expected: FAIL — `TODO()`.

- [ ] **Step 3: Implement `planFor`**

Replace the stub:

```kotlin
    fun planFor(componentFile: PsiFile, name: String, isDefault: Boolean, project: Project): Plan? {
        val vf = componentFile.originalFile.virtualFile ?: return null
        val componentDir = vf.parent ?: return null
        val chain = barrelChain(componentDir, project)
        if (chain.isEmpty()) return null

        val edits = mutableListOf<BarrelEdit>()
        var exposedAsDefault = isDefault
        for ((i, dir) in chain.withIndex()) {
            val index = indexFileIn(dir) ?: continue
            val target = if (i == 0) vf else chain[i - 1]
            val spec = relativeSpecifier(dir, target)
            val text = runCatching { VfsUtilCore.loadText(index) }.getOrDefault("")
            if (forwardsName(text, name, spec)) { exposedAsDefault = false; continue }
            if (exposedAsDefault && forwardsDefaultFrom(text, spec)) continue // keep default exposure for the parent
            val line = reExportLine(name, exposedAsDefault, spec, detectStyle(text))
            edits += BarrelEdit(index, line)
            exposedAsDefault = false
        }
        if (edits.isEmpty()) return null

        val cap = sourceRoot(componentDir, project)
        val top = chain.last()
        val label = (if (cap != null) VfsUtilCore.getRelativePath(top, cap) else null) ?: top.name
        return Plan(label, edits)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.barrels.BarrelExportsPlanTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/barrels/BarrelExports.kt src/test/kotlin/com/webdx/barrels/BarrelExportsPlanTest.kt
git commit -m "feat(barrels): planFor — chain fold with default->named conversion and dedup"
```

---

### Task 7: Wire the intention (availability, dynamic text, apply)

**Files:**
- Modify: `src/main/kotlin/com/webdx/barrels/BarrelExportIntention.kt`
- Test: `src/test/kotlin/com/webdx/barrels/BarrelExportIntentionTest.kt`

**Interfaces:**
- Consumes: `BarrelExports.exportedNameAt`, `BarrelExports.planFor`, `com.webdx.cssmodules.CssModules.isJsLikeFileName`.
- Produces: a working Alt+Enter intention named family "Export through barrel modules".

- [ ] **Step 1: Write the failing test**

```kotlin
package com.webdx.barrels

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BarrelExportIntentionTest : BasePlatformTestCase() {

    private val family = "Export through barrel modules"
    private fun tsconfig() =
        myFixture.addFileToProject("tsconfig.json", """{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
    private fun textOf(path: String) =
        myFixture.findFileInTempDir(path).let { com.intellij.openapi.vfs.VfsUtilCore.loadText(it) }

    fun testOfferedAndAppliesIntchShape() {
        tsconfig()
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './Other'\n")
        myFixture.addFileToProject("components/Button/index.ts", "export * from './placeholder'\n")
        myFixture.configureByText("components/Button/Button.tsx", "export const But<caret>ton = () => null\n")
        val intention = myFixture.filterAvailableIntentions(family).first()
        myFixture.launchAction(intention)
        assertTrue(textOf("components/Button/index.ts").contains("export * from './Button'"))
        assertTrue(textOf("components/index.ts").contains("export * from './Button'"))
    }

    fun testAppliesDefaultExportConversion() {
        tsconfig()
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("src/containers/index.ts", "export * from './Basic'\n")
        myFixture.addFileToProject("src/containers/Touchpoint/index.ts", "export { default } from './Touchpoint'\n")
        myFixture.configureByText(
            "src/containers/Touchpoint/Touchpoint.tsx",
            "export default function Touch<caret>point() {}\n",
        )
        myFixture.launchAction(myFixture.filterAvailableIntentions(family).first())
        assertTrue(
            textOf("src/containers/index.ts").contains("export { default as Touchpoint } from './Touchpoint'"),
        )
    }

    fun testNotOfferedInIndexFile() {
        tsconfig()
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.configureByText("components/index.ts", "export const Hel<caret>per = 1\n")
        assertEmpty(myFixture.filterAvailableIntentions(family))
    }

    fun testNotOfferedWithoutBarrelAncestor() {
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.configureByText("loose/Button.tsx", "export const But<caret>ton = 1\n")
        assertEmpty(myFixture.filterAvailableIntentions(family))
    }

    fun testNotOfferedWhenAlreadyWired() {
        tsconfig()
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './Button'\n")
        myFixture.addFileToProject("components/Button/index.ts", "export * from './Button'\n")
        myFixture.configureByText("components/Button/Button.tsx", "export const But<caret>ton = 1\n")
        assertEmpty(myFixture.filterAvailableIntentions(family))
    }

    fun testNotOfferedForNonExported() {
        tsconfig()
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './x'\n")
        myFixture.configureByText("components/Button/Button.tsx", "const Pri<caret>vate = 1\n")
        assertEmpty(myFixture.filterAvailableIntentions(family))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.barrels.BarrelExportIntentionTest"`
Expected: FAIL — the stub is always unavailable, so `filterAvailableIntentions(family)` is empty and the first two tests fail on `.first()`.

- [ ] **Step 3: Implement the intention**

Replace `BarrelExportIntention.kt` entirely:

```kotlin
package com.webdx.barrels

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.webdx.cssmodules.CssModules

/**
 * Alt+Enter on an exported top-level symbol: re-export it through every existing index.ts(x) barrel
 * up the tree, to the auto-detected module root. Writes only barrels (never consumer files), never
 * creates missing index files, matches each target file's style, and applies all edits as one
 * undoable command. Delegates all logic to [BarrelExports].
 */
class BarrelExportIntention : PsiElementBaseIntentionAction() {

    @Volatile private var label: String? = null

    override fun getFamilyName(): String = "Export through barrel modules"

    override fun getText(): String = "Export through barrels up to ${label ?: "module"}"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val file = element.containingFile?.originalFile ?: return false
        val name = file.name
        if (!CssModules.isJsLikeFileName(name)) return false
        if (name.substringBeforeLast('.').equals("index", ignoreCase = true)) return false
        val (symbol, isDefault) = BarrelExports.exportedNameAt(element) ?: return false
        val plan = BarrelExports.planFor(file, symbol, isDefault, project) ?: return false
        label = plan.moduleRootLabel
        return plan.edits.isNotEmpty()
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val file = element.containingFile?.originalFile ?: return
        val (symbol, isDefault) = BarrelExports.exportedNameAt(element) ?: return
        val plan = BarrelExports.planFor(file, symbol, isDefault, project) ?: return
        val pdm = PsiDocumentManager.getInstance(project)
        WriteCommandAction.runWriteCommandAction(project, getFamilyName(), null, {
            for (edit in plan.edits) {
                val doc = FileDocumentManager.getInstance().getDocument(edit.indexFile) ?: continue
                val cur = doc.text
                val prefix = if (cur.isEmpty() || cur.endsWith("\n")) "" else "\n"
                doc.insertString(doc.textLength, prefix + edit.line + "\n")
                pdm.commitDocument(doc)
            }
        })
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.barrels.BarrelExportIntentionTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Run the whole suite**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL — all prior tests plus the new ones green.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/webdx/barrels/BarrelExportIntention.kt src/test/kotlin/com/webdx/barrels/BarrelExportIntentionTest.kt
git commit -m "feat(barrels): wire Alt+Enter intention (availability, dynamic text, one-shot apply)"
```

---

### Task 8: Documentation + version bump

**Files:**
- Modify: `README.md` (new "Barrel exports" bullet/section + architecture entry + EP table row + test-class row)
- Modify: `CHANGELOG.md` (new version entry at the top)
- Modify: `build.gradle.kts` (bump `version`)

**Interfaces:** none (docs only).

- [ ] **Step 1: Bump the version**

In `build.gradle.kts`, change the `version = "1.9.2"` line to the next minor:

```kotlin
version = "1.10.0"
```

- [ ] **Step 2: Add the CHANGELOG entry**

Insert at the top of `CHANGELOG.md` (after the intro paragraph, before `## 1.9.2`):

```markdown
## 1.10.0 — 2026-06-22
- **New: "Export through barrel modules" intention** (`com.webdx.barrels`). Alt+Enter on an
  exported top-level symbol (`export const/function/class`, `export default`, local `export { X }`)
  writes `export … from` re-exports into every existing `index.ts(x)` up the directory tree, up to
  the auto-detected module root. The module root is the first ancestor with a `package.json` or whose
  `index` is a tsconfig path-alias target, else the highest existing index below the source-root
  (`@/`/`baseUrl`). Missing `index` files are skipped (never created) and the relative path is adjusted
  accordingly (multi-segment when levels are skipped). Each line matches the target file's style
  (quotes, semicolons, `export *` vs named); a default export uses `export { default as X }`
  (because `export *` does not carry `default`), converting to a named re-export at the lowest level.
  Already-wired levels are skipped (no duplicate lines); when nothing needs adding the intention is not
  offered. Consumer files are never touched, and all edits apply as one undoable command. Source-resolved
  (no TS service). (`BarrelExports`, `BarrelExportIntention`.)
```

- [ ] **Step 3: Add the README bullet**

In `README.md`, under "Feature areas", add a new top-level bullet:

```markdown
- **Barrel exports** — Alt+Enter on an exported component name re-exports it through every existing
  `index.ts(x)` up to the auto-detected module root (`package.json` / tsconfig-alias target / highest
  index below the `@/` source-root). Skips missing index files (adjusting the path), matches each
  barrel's style, handles default exports (`export { default as X }`), de-dups already-wired levels,
  and applies all edits as one undoable command. Never edits consumer files or creates index files.
```

Add to the architecture file list:

```markdown
src/main/kotlin/com/webdx/barrels/
  BarrelExports.kt                    // boundary detection, chain walk, path/form, dedup
  BarrelExportIntention.kt            // Alt+Enter: export through barrel modules
```

Add an EP-table row:

```markdown
| Export through barrel modules | `intentionAction` | `com.intellij` |
```

Add two test-class rows to the "What's covered" table:

```markdown
| `BarrelExportsTextTest` / `…PathTest` / `…ChainTest` / `…SymbolTest` / `…PlanTest` | `BarrelExports` logic: style/line/dedup, index/source-root/module-root, chain+specifier, caret symbol, full plan (intch + muse + default + already-wired) |
| `BarrelExportIntentionTest` | availability + apply via the real intention (intch star shape, default-export conversion; not offered in index/without barrel/already-wired/non-exported) |
```

- [ ] **Step 4: Verify docs build is unaffected (compile only)**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add README.md CHANGELOG.md build.gradle.kts
git commit -m "docs(barrels): README + CHANGELOG for barrel-export intention; bump 1.10.0"
```

---

## Self-Review

**1. Spec coverage:**
- Module-boundary detection (package.json → alias target → highest index up to source-root) → Tasks 3 (`isModuleRoot`, `sourceRoot`) + 4 (`barrelChain`). ✓
- Skip missing index, adjust path (multi-segment) → Task 4 (`barrelChain` skip, `relativeSpecifier`) + Task 6 plan test `testMuseNamedStyleMultiSegmentWithSemicolons`. ✓
- Re-export form (named-star vs named, `default as X`, default→named conversion) → Task 2 (`reExportLine`) + Task 6 (`planFor` fold) + tests. ✓
- Style from target file (quotes, `;`, star vs named) → Task 2 (`detectStyle`). ✓
- Intention availability/dedup/dynamic text/one-shot WriteCommandAction → Task 7 + tests. ✓
- Default-export `TouchpointCreate` case → Task 6 `testTouchpointDefaultExportConversion` + Task 7 `testAppliesDefaultExportConversion`. ✓
- Both real project shapes (intch every-level/star/no-semi; muse single-barrel/named/semi/multi-segment) → Tasks 4, 6, 7. ✓
- Not offered when not exported / in index / no barrel / fully wired → Task 7. ✓
- Out of scope (no consumer edits, no index creation, no auto-`export`) → enforced by construction; nothing implements them. ✓
- Docs + version bump → Task 8. ✓

**2. Placeholder scan:** Task 1 intentionally ships `TODO()` stubs, each removed by a named later task (annotated `TODO("Task N")`); every other step contains complete code. No "add error handling"/"write tests for the above" placeholders.

**3. Type consistency:** `Style`/`BarrelEdit`/`Plan` shapes are defined in Task 1 and used unchanged in Tasks 2/6/7. `planFor(componentFile, name, isDefault, project)` signature matches its callers in Task 7. `exportedNameAt` returns `Pair<String, Boolean>` consistently. `sourceRoot`/`isModuleRoot`/`indexFileIn` signatures match between definition (Task 3) and use (Tasks 4, 6). `CssModules.tsconfigAliases(text).baseUrl/paths` matches the verified API in `CssModuleShared.kt`.

One risk flagged inline (Task 5 Step 4): `ES6ExportDefaultAssignment.namedElement` may be `getNamedElement()` in this SDK — the step says to adjust if the test fails.
