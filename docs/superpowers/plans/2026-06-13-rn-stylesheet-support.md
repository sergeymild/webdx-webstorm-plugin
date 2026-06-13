# React Native `StyleSheet.create` Support — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the CSS-module DX features (go-to, scoped Find Usages, unknown/unused-key inspections) to React Native `StyleSheet.create` styles, resolved from source PSI so they work even when the TS service does not.

**Architecture:** A new package `com.webdx.rnstyles` with a shared `RnStyles` helper object (analog of `CssModules`). A "StyleSheet object" is a `JSCallExpression` `StyleSheet.create({…})`; its top-level object-literal properties are the style keys. Two inspections + one Find Usages factory consume the helper; go-to is folded into the existing `CssModuleGotoDeclarationAction` (the platform allows only one overriding `GotoDeclaration` action). Destructuring (`const { title } = styles`) is first-class across navigation, find-usages, and unused-detection. No completion, no composition chains, no default-export support.

**Tech Stack:** Kotlin 2.3.0, IntelliJ Platform Gradle Plugin 2.6.0, JS PSI (`com.intellij.lang.javascript.psi.*` — structural, not the TS service), JUnit 4 on `BasePlatformTestCase`. Reuses `com.webdx.cssmodules.CssModules` helpers (`prevMeaningfulLeaf`, `nextMeaningfulLeaf`, `resolveImportPath`, `isJsLikeFileName`).

Build/test command (used in every "run" step below):
```
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test
```
Run a single test class by adding `--tests "com.webdx.rnstyles.<Class>"`.

Reference spec: `docs/superpowers/specs/2026-06-13-rn-stylesheet-support-design.md`.

---

## Task 1: Pure string helpers (`parseNamedImports`, `parseDestructuredEntries`)

**Files:**
- Create: `src/main/kotlin/com/webdx/rnstyles/RnStyles.kt`
- Test: `src/test/kotlin/com/webdx/rnstyles/RnStylesLogicTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/webdx/rnstyles/RnStylesLogicTest.kt`:
```kotlin
package com.webdx.rnstyles

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure (no-PSI) tests for the import/destructuring brace parsers. */
class RnStylesLogicTest {

    @Test fun parsesPlainNamedImports() {
        assertEquals(mapOf("styles" to "styles"), RnStyles.parseNamedImports(" styles "))
        assertEquals(mapOf("a" to "a", "styles" to "styles"), RnStyles.parseNamedImports("a, styles"))
    }

    @Test fun parsesAliasedNamedImports() {
        assertEquals(mapOf("s" to "styles"), RnStyles.parseNamedImports("styles as s"))
        assertEquals(mapOf("a" to "a", "s" to "styles"), RnStyles.parseNamedImports("a, styles as s"))
    }

    @Test fun parsesShorthandDestructuring() {
        assertEquals(mapOf("title" to "title", "text" to "text"), RnStyles.parseDestructuredEntries("title, text"))
    }

    @Test fun parsesRenamedAndDefaultedDestructuring() {
        // `{ title: t }` -> local `t` from key `title`; `{ x = 5 }` -> key `x`; `...rest` ignored.
        assertEquals(
            mapOf("t" to "title", "x" to "x"),
            RnStyles.parseDestructuredEntries("title: t, x = 5, ...rest"),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "com.webdx.rnstyles.RnStylesLogicTest"`
Expected: FAIL — `RnStyles` / file does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

`src/main/kotlin/com/webdx/rnstyles/RnStyles.kt`:
```kotlin
package com.webdx.rnstyles

/**
 * Shared helpers for React Native `StyleSheet.create` styles, all on generic JS PSI
 * (no TypeScript language service). A "StyleSheet object" is a `StyleSheet.create({…})`
 * call; its top-level object-literal properties are the style keys.
 */
internal object RnStyles {

    /** Parse the inside of `import { … }` braces -> localName -> originalExportName. */
    fun parseNamedImports(braceContent: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in braceContent.split(',')) {
            val entry = raw.trim()
            if (entry.isEmpty()) continue
            val parts = entry.split(Regex("""\s+as\s+"""))
            val orig = parts[0].trim()
            val local = parts.getOrNull(1)?.trim() ?: orig
            if (orig.isNotEmpty() && local.isNotEmpty()) out[local] = orig
        }
        return out
    }

    /** Parse the inside of destructuring `const { … } = x` braces -> localName -> sourceKeyName. */
    fun parseDestructuredEntries(braceContent: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in braceContent.split(',')) {
            val entry = raw.trim().substringBefore('=').trim() // drop default values
            if (entry.isEmpty() || entry.startsWith("...")) continue
            val parts = entry.split(':')
            val key = parts[0].trim()
            val local = parts.getOrNull(1)?.trim() ?: key
            if (key.isNotEmpty() && local.isNotEmpty()) out[local] = key
        }
        return out
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: same as Step 2.
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/rnstyles/RnStyles.kt src/test/kotlin/com/webdx/rnstyles/RnStylesLogicTest.kt
git commit -m "feat(rnstyles): pure import/destructuring brace parsers"
```

---

## Task 2: Detect StyleSheet objects + extract keys

**Files:**
- Modify: `src/main/kotlin/com/webdx/rnstyles/RnStyles.kt`
- Test: `src/test/kotlin/com/webdx/rnstyles/RnStylesPsiTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/webdx/rnstyles/RnStylesPsiTest.kt`:
```kotlin
package com.webdx.rnstyles

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** PSI tests for the RnStyles helpers, on in-memory JS/TS fixtures. */
class RnStylesPsiTest : BasePlatformTestCase() {

    fun testFindsStyleSheetAndKeys() {
        val file = myFixture.configureByText(
            "styles.ts",
            """
            import { StyleSheet } from 'react-native'
            export const styles = StyleSheet.create({
                container: { flex: 1 },
                title: { fontSize: 16 },
            })
            """.trimIndent(),
        )
        val sheets = RnStyles.fileStyleSheets(file)
        assertEquals(setOf("styles"), sheets.keys)
        assertEquals(listOf("container", "title"), RnStyles.styleKeys(sheets.getValue("styles")))
        assertNotNull(RnStyles.keyProperty(sheets.getValue("styles"), "title"))
        assertNull(RnStyles.keyProperty(sheets.getValue("styles"), "nope"))
    }

    fun testIgnoresNonStyleSheetObjects() {
        val file = myFixture.configureByText(
            "styles.ts",
            "const notStyles = ({ a: 1 });\nconst x = SomethingElse.create({ b: 2 });",
        )
        assertTrue(RnStyles.fileStyleSheets(file).isEmpty())
    }

    fun testInlineNonExportedBindingNameIsAny() {
        val file = myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\nconst expStyles = StyleSheet.create({ row: { flex: 1 } })",
        )
        assertEquals(setOf("expStyles"), RnStyles.fileStyleSheets(file).keys)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew test --tests "com.webdx.rnstyles.RnStylesPsiTest"`
Expected: FAIL — `fileStyleSheets`/`styleKeys`/`keyProperty` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add these imports at the top of `RnStyles.kt`:
```kotlin
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
```

Add inside `object RnStyles`:
```kotlin
    // ---- detection ----

    fun isStyleSheetCreateCall(call: JSCallExpression): Boolean =
        call.methodExpression?.text?.trim() == "StyleSheet.create"

    /** The first-argument object literal of a `StyleSheet.create({...})` call, else null. */
    fun styleSheetObjectOf(call: JSCallExpression): JSObjectLiteralExpression? {
        if (!isStyleSheetCreateCall(call)) return null
        return call.arguments.firstOrNull() as? JSObjectLiteralExpression
    }

    /** Top-level property names of a StyleSheet object (computed/spread/unnamed skipped). */
    fun styleKeys(obj: JSObjectLiteralExpression): List<String> =
        obj.properties.mapNotNull { it.name }.distinct()

    fun keyProperty(obj: JSObjectLiteralExpression, name: String): JSProperty? =
        obj.properties.firstOrNull { it.name == name }

    /** The variable name a StyleSheet object is assigned to (`const <name> = StyleSheet.create(...)`). */
    fun bindingNameOf(obj: JSObjectLiteralExpression): String? =
        PsiTreeUtil.getParentOfType(obj, JSVariable::class.java)?.name

    /** Every `<name> = StyleSheet.create({...})` in [file]: binding name -> object literal. */
    fun fileStyleSheets(file: PsiFile): Map<String, JSObjectLiteralExpression> {
        val out = LinkedHashMap<String, JSObjectLiteralExpression>()
        for (call in PsiTreeUtil.collectElementsOfType(file, JSCallExpression::class.java)) {
            val obj = styleSheetObjectOf(call) ?: continue
            val name = PsiTreeUtil.getParentOfType(call, JSVariable::class.java)?.name ?: continue
            out.putIfAbsent(name, obj)
        }
        return out
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: same as Step 2.
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/rnstyles/RnStyles.kt src/test/kotlin/com/webdx/rnstyles/RnStylesPsiTest.kt
git commit -m "feat(rnstyles): detect StyleSheet.create objects and extract keys"
```

---

## Task 3: Resolve a binding to its StyleSheet object (local + named import)

**Files:**
- Modify: `src/main/kotlin/com/webdx/rnstyles/RnStyles.kt`
- Test: `src/test/kotlin/com/webdx/rnstyles/RnStylesPsiTest.kt`

- [ ] **Step 1: Write the failing test (append methods to `RnStylesPsiTest`)**

```kotlin
    fun testResolvesLocalBinding() {
        val file = myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\nconst styles = StyleSheet.create({ row: { flex: 1 } })\nconst x = styles.row",
        )
        val obj = RnStyles.resolveStyleSheetForBinding(file, "styles")
        assertNotNull(obj)
        assertEquals(listOf("row"), RnStyles.styleKeys(obj!!))
    }

    fun testResolvesNamedImportBinding() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ title: { fontSize: 16 } })",
        )
        val file = myFixture.configureByText(
            "About.tsx",
            "import { styles } from './styles'\nconst x = styles.title",
        )
        val obj = RnStyles.resolveStyleSheetForBinding(file, "styles")
        assertNotNull(obj)
        assertEquals(listOf("title"), RnStyles.styleKeys(obj!!))
    }

    fun testResolvesAliasedNamedImportBinding() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ box: { flex: 1 } })",
        )
        val file = myFixture.configureByText(
            "About.tsx",
            "import { styles as s } from './styles'\nconst x = s.box",
        )
        val obj = RnStyles.resolveStyleSheetForBinding(file, "s")
        assertNotNull(obj)
        assertEquals(listOf("box"), RnStyles.styleKeys(obj!!))
    }

    fun testUnknownBindingResolvesNull() {
        val file = myFixture.configureByText("About.tsx", "const x = nope.title")
        assertNull(RnStyles.resolveStyleSheetForBinding(file, "nope"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew test --tests "com.webdx.rnstyles.RnStylesPsiTest"`
Expected: FAIL — `resolveStyleSheetForBinding` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add imports to `RnStyles.kt`:
```kotlin
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.webdx.cssmodules.CssModules
```

Add inside `object RnStyles`:
```kotlin
    private val JS_EXTS = listOf("ts", "tsx", "js", "jsx", "mts", "cts", "mjs", "cjs")

    /** Resolve a JS/TS import [path] (extensionless allowed) to a VirtualFile. */
    fun resolveJsImport(fromDir: VirtualFile, project: Project, path: String): VirtualFile? {
        CssModules.resolveImportPath(fromDir, project, path)?.let { if (!it.isDirectory) return it }
        for (ext in JS_EXTS) CssModules.resolveImportPath(fromDir, project, "$path.$ext")?.let { return it }
        for (ext in JS_EXTS) CssModules.resolveImportPath(fromDir, project, "$path/index.$ext")?.let { return it }
        return null
    }

    /**
     * Resolve qualifier [binding] used in [jsFile] to its StyleSheet object:
     * a same-file `const <binding> = StyleSheet.create(...)` first, else a named import
     * `import { <binding> } from '...'` -> the exported StyleSheet object in the target file.
     */
    fun resolveStyleSheetForBinding(jsFile: PsiFile, binding: String): JSObjectLiteralExpression? {
        val file = jsFile.originalFile
        fileStyleSheets(file)[binding]?.let { return it }
        val (targetFile, exportName) = resolveNamedImport(file, binding) ?: return null
        return fileStyleSheets(targetFile)[exportName]
    }

    /** For [binding] in [jsFile], find `import { ... } from 'path'` -> (target file, original export name). */
    private fun resolveNamedImport(jsFile: PsiFile, binding: String): Pair<PsiFile, String>? {
        val dir = jsFile.virtualFile?.parent ?: return null
        val project = jsFile.project
        val psiManager = PsiManager.getInstance(project)
        for (m in NAMED_IMPORT.findAll(jsFile.text)) {
            val orig = parseNamedImports(m.groupValues[1])[binding] ?: continue
            val vf = resolveJsImport(dir, project, m.groupValues[2]) ?: continue
            val target = psiManager.findFile(vf) ?: continue
            return target to orig
        }
        return null
    }

    internal val NAMED_IMPORT = Regex("""import\s*\{([^}]*)\}\s*from\s*['"]([^'"]+)['"]""")
```

- [ ] **Step 4: Run test to verify it passes**

Run: same as Step 2.
Expected: PASS (now 7 tests in the class).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/rnstyles/RnStyles.kt src/test/kotlin/com/webdx/rnstyles/RnStylesPsiTest.kt
git commit -m "feat(rnstyles): resolve local + named-import style bindings"
```

---

## Task 4: Destructuring resolution + `resolveKeyProperty` (go-to core)

**Files:**
- Modify: `src/main/kotlin/com/webdx/rnstyles/RnStyles.kt`
- Test: `src/test/kotlin/com/webdx/rnstyles/RnStyleGotoDeclarationTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/webdx/rnstyles/RnStyleGotoDeclarationTest.kt`:
```kotlin
package com.webdx.rnstyles

import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * `RnStyles.resolveKeyProperty` (the go-to / find-usages core): an identifier at the
 * caret resolves to the single `JSProperty` declaring that style key — from a
 * `styles.<key>` member access, from a destructured local, and from the destructuring
 * site itself; for local, imported, and aliased bindings.
 */
class RnStyleGotoDeclarationTest : BasePlatformTestCase() {

    private fun propAtCaret(): JSProperty? {
        val el = myFixture.file.findElementAt(myFixture.caretOffset) ?: return null
        return RnStyles.resolveKeyProperty(el)
    }

    fun testMemberAccessLocal() {
        myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ row: { flex: 1 } })\n" +
                "const x = styles.ro<caret>w",
        )
        val prop = propAtCaret()
        assertNotNull("expected a key property", prop)
        assertEquals("row", prop!!.name)
        assertEquals("Comp.tsx", prop.containingFile?.name)
    }

    fun testMemberAccessImported() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ title: { fontSize: 16 } })",
        )
        myFixture.configureByText(
            "About.tsx",
            "import { styles } from './styles'\nconst x = styles.tit<caret>le",
        )
        val prop = propAtCaret()
        assertEquals("title", prop?.name)
        assertEquals("styles.ts", prop?.containingFile?.name)
    }

    fun testDestructuredLocalAndSite() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ title: { fontSize: 16 } })",
        )
        // caret on the *use* of the destructured local
        myFixture.configureByText(
            "About.tsx",
            "import { styles } from './styles'\nconst { title } = styles\nconst x = tit<caret>le",
        )
        assertEquals("title", propAtCaret()?.name)

        // caret on the destructuring *site* must also resolve
        myFixture.configureByText(
            "About2.tsx",
            "import { styles } from './styles'\nconst { tit<caret>le } = styles",
        )
        assertEquals("title", propAtCaret()?.name)
    }

    fun testUnknownKeyAndUnrelatedQualifierResolveNull() {
        myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ row: { flex: 1 } })\n" +
                "const obj = { foo: 1 }\nconst x = obj.fo<caret>o",
        )
        assertNull("unrelated qualifier must not resolve", propAtCaret())

        myFixture.configureByText(
            "Comp2.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ row: { flex: 1 } })\n" +
                "const x = styles.no<caret>pe",
        )
        assertNull("unknown key must not resolve", propAtCaret())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew test --tests "com.webdx.rnstyles.RnStyleGotoDeclarationTest"`
Expected: FAIL — `resolveKeyProperty` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add import to `RnStyles.kt`:
```kotlin
import com.intellij.psi.PsiElement
```

Add inside `object RnStyles`:
```kotlin
    private val DESTRUCTURE = Regex("""(?:const|let|var)\s*\{([^}]*)\}\s*=\s*([A-Za-z_$][\w$]*)""")

    /** local name -> (StyleSheet object, source key) for `const { … } = <styles binding>` in [file]. */
    fun destructuredKeys(file: PsiFile): Map<String, Pair<JSObjectLiteralExpression, String>> {
        val real = file.originalFile
        val out = LinkedHashMap<String, Pair<JSObjectLiteralExpression, String>>()
        for (m in DESTRUCTURE.findAll(real.text)) {
            val obj = resolveStyleSheetForBinding(real, m.groupValues[2]) ?: continue
            for ((local, key) in parseDestructuredEntries(m.groupValues[1])) out.putIfAbsent(local, obj to key)
        }
        return out
    }

    /**
     * Resolve the identifier leaf [element] to the StyleSheet key property it refers to:
     *  - a `<binding>.<key>` member access, or
     *  - a local destructured from a StyleSheet object (incl. the destructuring site).
     * Resolves against `originalFile` (navigation hands a non-physical copy — CSS gotcha #8).
     */
    fun resolveKeyProperty(element: PsiElement): JSProperty? {
        if (element.firstChild != null) return null // leaves only
        val name = element.text
        if (name.isEmpty() || !name.first().isJavaIdentifierStart()) return null
        val file = element.containingFile?.originalFile ?: return null
        if (!CssModules.isJsLikeFileName(file.name)) return null

        val dot = CssModules.prevMeaningfulLeaf(element)
        if (dot != null && dot.text == ".") { // Case A: <binding>.<name>
            val qualifier = CssModules.prevMeaningfulLeaf(dot) ?: return null
            val obj = resolveStyleSheetForBinding(file, qualifier.text) ?: return null
            return keyProperty(obj, name)
        }

        // Case B: a local destructured from a StyleSheet object (incl. the destructuring site)
        val (obj, key) = destructuredKeys(file)[name] ?: return null
        return keyProperty(obj, key)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: same as Step 2.
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/rnstyles/RnStyles.kt src/test/kotlin/com/webdx/rnstyles/RnStyleGotoDeclarationTest.kt
git commit -m "feat(rnstyles): resolve style key property (member access + destructuring)"
```

---

## Task 5: Scope helpers — `bindingsInFile`, importers, `collectUsedKeys`

**Files:**
- Modify: `src/main/kotlin/com/webdx/rnstyles/RnStyles.kt`
- Test: `src/test/kotlin/com/webdx/rnstyles/RnStylesPsiTest.kt`

- [ ] **Step 1: Write the failing test (append to `RnStylesPsiTest`)**

```kotlin
    fun testBindingsInFileIncludesLocalAndImported() {
        myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ title: { fontSize: 16 } })",
        )
        val file = myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "import { styles } from './styles'\n" +
                "const local = StyleSheet.create({ row: { flex: 1 } })\n" +
                "const x = styles.title\nconst y = local.row",
        )
        val bindings = RnStyles.bindingsInFile(file)
        assertEquals(setOf("styles", "local"), bindings.keys)
        assertEquals(listOf("title"), RnStyles.styleKeys(bindings.getValue("styles")))
        assertEquals(listOf("row"), RnStyles.styleKeys(bindings.getValue("local")))
    }

    fun testCollectUsedKeysInline() {
        val file = myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ used: { flex: 1 }, dead: { flex: 1 }, viaDestr: { flex: 1 } })\n" +
                "const { viaDestr } = styles\nconst x = styles.used\nconst y = viaDestr",
        )
        val obj = RnStyles.fileStyleSheets(file).getValue("styles")
        assertEquals(setOf("used", "viaDestr"), RnStyles.collectUsedKeys(obj))
    }

    fun testCollectUsedKeysExportedViaImporter() {
        val stylesPsi = myFixture.addFileToProject(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ used: { flex: 1 }, dead: { flex: 1 } })",
        )
        myFixture.addFileToProject(
            "About.tsx",
            "import { styles } from './styles'\nconst x = styles.used",
        )
        val obj = RnStyles.fileStyleSheets(stylesPsi).getValue("styles")
        assertEquals(setOf("used"), RnStyles.collectUsedKeys(obj))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew test --tests "com.webdx.rnstyles.RnStylesPsiTest"`
Expected: FAIL — `bindingsInFile`/`collectUsedKeys` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add imports to `RnStyles.kt`:
```kotlin
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
```

Add inside `object RnStyles`:
```kotlin
    /** All StyleSheet bindings usable in [file]: local consts + named imports (local name -> object). */
    fun bindingsInFile(file: PsiFile): Map<String, JSObjectLiteralExpression> {
        val real = file.originalFile
        val out = LinkedHashMap<String, JSObjectLiteralExpression>()
        out.putAll(fileStyleSheets(real))
        val dir = real.virtualFile?.parent ?: return out
        val psiManager = PsiManager.getInstance(real.project)
        for (m in NAMED_IMPORT.findAll(real.text)) {
            val vf = resolveJsImport(dir, real.project, m.groupValues[2]) ?: continue
            val target = psiManager.findFile(vf) ?: continue
            val exported = fileStyleSheets(target)
            for ((local, orig) in parseNamedImports(m.groupValues[1])) {
                exported[orig]?.let { out.putIfAbsent(local, it) }
            }
        }
        return out
    }

    /** True if the StyleSheet object is declared with `export` (so it can be consumed elsewhere). */
    fun isExported(obj: JSObjectLiteralExpression): Boolean {
        val stmt = PsiTreeUtil.getParentOfType(obj, JSVarStatement::class.java) ?: return false
        return stmt.text.trimStart().startsWith("export")
    }

    /** Files importing [exportName] from [stylesFile], each mapped to the local binding name(s) used. */
    fun importersForExport(stylesFile: PsiFile, exportName: String): Map<PsiFile, Set<String>> {
        val project = stylesFile.project
        val target = stylesFile.virtualFile ?: return emptyMap()
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val out = LinkedHashMap<PsiFile, MutableSet<String>>()
        for (ext in JS_EXTS) {
            for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                if (vf == target) continue
                val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: continue
                if (!text.contains(exportName)) continue
                val f = psiManager.findFile(vf) ?: continue
                val locals = localBindingsForExport(f, target, exportName)
                if (locals.isNotEmpty()) out.getOrPut(f) { linkedSetOf() }.addAll(locals)
            }
        }
        return out
    }

    private fun localBindingsForExport(file: PsiFile, target: VirtualFile, exportName: String): Set<String> {
        val dir = file.virtualFile?.parent ?: return emptySet()
        val locals = linkedSetOf<String>()
        for (m in NAMED_IMPORT.findAll(file.text)) {
            if (resolveJsImport(dir, file.project, m.groupValues[2]) != target) continue
            for ((local, orig) in parseNamedImports(m.groupValues[1])) if (orig == exportName) locals.add(local)
        }
        return locals
    }

    /** Style keys of [obj] referenced (as `<binding>.<key>` or via destructuring) across its scope. */
    fun collectUsedKeys(obj: JSObjectLiteralExpression): Set<String> {
        val definingFile = obj.containingFile?.originalFile ?: return emptySet()
        val binding = bindingNameOf(obj) ?: return emptySet()
        val keys = styleKeys(obj).toSet()
        if (keys.isEmpty()) return emptySet()

        val scope = LinkedHashMap<PsiFile, MutableSet<String>>()
        scope.getOrPut(definingFile) { linkedSetOf() }.add(binding)
        if (isExported(obj)) {
            for ((f, locals) in importersForExport(definingFile, binding)) {
                scope.getOrPut(f) { linkedSetOf() }.addAll(locals)
            }
        }

        val used = HashSet<String>()
        for ((file, bindings) in scope) {
            PsiTreeUtil.collectElements(file) { it.firstChild == null && it.text == "." }.forEach { dot ->
                val q = CssModules.prevMeaningfulLeaf(dot) ?: return@forEach
                if (q.text !in bindings) return@forEach
                val member = CssModules.nextMeaningfulLeaf(dot) ?: return@forEach
                if (member.text in keys) used.add(member.text)
            }
            for (m in DESTRUCTURE.findAll(file.text)) {
                if (m.groupValues[2] !in bindings) continue
                for ((_, key) in parseDestructuredEntries(m.groupValues[1])) if (key in keys) used.add(key)
            }
        }
        return used
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: same as Step 2.
Expected: PASS (now 10 tests in the class).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/webdx/rnstyles/RnStyles.kt src/test/kotlin/com/webdx/rnstyles/RnStylesPsiTest.kt
git commit -m "feat(rnstyles): scope helpers (bindings-in-file, importers, used keys)"
```

---

## Task 6: "Unknown style key" inspection + registration

**Files:**
- Create: `src/main/kotlin/com/webdx/rnstyles/RnStyleUnknownKeyInspection.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/webdx/rnstyles/RnStyleUnknownKeyInspectionTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/webdx/rnstyles/RnStyleUnknownKeyInspectionTest.kt`:
```kotlin
package com.webdx.rnstyles

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RnStyleUnknownKeyInspectionTest : BasePlatformTestCase() {

    fun testUnknownKeyInlineReported() {
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ container: { flex: 1 } })\n" +
                "const a = styles.container\nconst b = styles.doesNotExist",
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(RnStyleUnknownKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "expected 'doesNotExist' flagged, got: $descriptions",
            descriptions.any { it.contains("Unknown style key 'doesNotExist'") },
        )
        assertFalse(
            "real key 'container' must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'container'") },
        )
    }

    fun testUnknownKeyImportedReported() {
        myFixture.addFileToProject(
            "src/styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ title: { fontSize: 16 } })",
        )
        val tsx = myFixture.addFileToProject(
            "src/About.tsx",
            "import { styles } from './styles'\nconst a = styles.title\nconst b = styles.nope",
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(RnStyleUnknownKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(descriptions.any { it.contains("Unknown style key 'nope'") }, "got: $descriptions")
        assertFalse(descriptions.any { it.contains("'title'") }, "got: $descriptions")
    }

    fun testUnrelatedMemberAccessNotFlagged() {
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            "const obj = { foo: 1 }\nconst x = obj.bar",
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(RnStyleUnknownKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(descriptions.any { it.contains("Unknown style key") }, "got: $descriptions")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew test --tests "com.webdx.rnstyles.RnStyleUnknownKeyInspectionTest"`
Expected: FAIL — `RnStyleUnknownKeyInspection` does not exist.

- [ ] **Step 3: Write the inspection**

`src/main/kotlin/com/webdx/rnstyles/RnStyleUnknownKeyInspection.kt`:
```kotlin
package com.webdx.rnstyles

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.webdx.cssmodules.CssModules

/**
 * Flags `<binding>.<key>` accesses where `<binding>` resolves to a `StyleSheet.create`
 * object and `<key>` is not one of its style keys. Only fires when the binding resolves
 * to a StyleSheet object, so unrelated member access is never redlined.
 */
class RnStyleUnknownKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        if (!CssModules.isJsLikeFileName(file.name)) return PsiElementVisitor.EMPTY_VISITOR

        val bindings = RnStyles.bindingsInFile(file).mapValues { RnStyles.styleKeys(it.value).toSet() }
        if (bindings.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.firstChild != null) return
                val name = element.text
                if (name.isEmpty() || !name.first().isJavaIdentifierStart()) return
                val dot = CssModules.prevMeaningfulLeaf(element) ?: return
                if (dot.text != ".") return
                val qualifier = CssModules.prevMeaningfulLeaf(dot) ?: return
                val keys = bindings[qualifier.text] ?: return
                if (name !in keys) {
                    holder.registerProblem(
                        element,
                        "Unknown style key '$name'",
                        ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`**

In the first `<extensions defaultExtensionNs="com.intellij">` block (the CSS one), after the `CssModuleUnknownClass*` inspections, add:
```xml
        <localInspection language="TypeScript JSX"
            shortName="RnStyleUnknownKeyTsx"
            displayName="Unknown React Native style key"
            groupName="JavaScript" enabledByDefault="true" level="ERROR"
            implementationClass="com.webdx.rnstyles.RnStyleUnknownKeyInspection"/>
        <localInspection language="TypeScript"
            shortName="RnStyleUnknownKeyTs"
            displayName="Unknown React Native style key"
            groupName="JavaScript" enabledByDefault="true" level="ERROR"
            implementationClass="com.webdx.rnstyles.RnStyleUnknownKeyInspection"/>
        <localInspection language="JavaScript"
            shortName="RnStyleUnknownKeyJs"
            displayName="Unknown React Native style key"
            groupName="JavaScript" enabledByDefault="true" level="ERROR"
            implementationClass="com.webdx.rnstyles.RnStyleUnknownKeyInspection"/>
        <localInspection language="ECMAScript 6"
            shortName="RnStyleUnknownKeyEs6"
            displayName="Unknown React Native style key"
            groupName="JavaScript" enabledByDefault="true" level="ERROR"
            implementationClass="com.webdx.rnstyles.RnStyleUnknownKeyInspection"/>
        <localInspection language="JSX Harmony"
            shortName="RnStyleUnknownKeyJsx"
            displayName="Unknown React Native style key"
            groupName="JavaScript" enabledByDefault="true" level="ERROR"
            implementationClass="com.webdx.rnstyles.RnStyleUnknownKeyInspection"/>
```

- [ ] **Step 5: Run test to verify it passes**

Run: same as Step 2.
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/webdx/rnstyles/RnStyleUnknownKeyInspection.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/webdx/rnstyles/RnStyleUnknownKeyInspectionTest.kt
git commit -m "feat(rnstyles): unknown style key inspection"
```

---

## Task 7: "Unused style key" inspection + registration

**Files:**
- Create: `src/main/kotlin/com/webdx/rnstyles/RnStyleUnusedKeyInspection.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/webdx/rnstyles/RnStyleUnusedKeyInspectionTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/webdx/rnstyles/RnStyleUnusedKeyInspectionTest.kt`:
```kotlin
package com.webdx.rnstyles

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RnStyleUnusedKeyInspectionTest : BasePlatformTestCase() {

    fun testUnusedKeyInlineReported() {
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ used: { flex: 1 }, boxRadius: { borderRadius: 4 } })\n" +
                "const x = styles.used",
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(RnStyleUnusedKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "expected 'boxRadius' flagged unused, got: $descriptions",
            descriptions.any { it.contains("'boxRadius'") && it.contains("not used") },
        )
        assertFalse(
            "'used' must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'used'") },
        )
    }

    fun testKeyUsedOnlyViaDestructuringNotFlagged() {
        val tsx = myFixture.addFileToProject(
            "src/Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ title: { fontSize: 16 } })\n" +
                "const { title } = styles\nconst x = title",
        )
        myFixture.configureFromExistingVirtualFile(tsx.virtualFile)
        myFixture.enableInspections(RnStyleUnusedKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "'title' is used via destructuring and must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'title'") },
        )
    }

    fun testExportedKeyUsedViaImporterNotFlagged() {
        myFixture.addFileToProject(
            "src/About.tsx",
            "import { styles } from './styles'\nconst x = styles.used",
        )
        val stylesTs = myFixture.addFileToProject(
            "src/styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ used: { flex: 1 }, dead: { flex: 1 } })",
        )
        myFixture.configureFromExistingVirtualFile(stylesTs.virtualFile)
        myFixture.enableInspections(RnStyleUnusedKeyInspection())

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "'used' is referenced by an importer and must NOT be flagged, got: $descriptions",
            descriptions.any { it.contains("'used'") },
        )
        assertTrue(
            "'dead' is never referenced and SHOULD be flagged, got: $descriptions",
            descriptions.any { it.contains("'dead'") && it.contains("not used") },
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew test --tests "com.webdx.rnstyles.RnStyleUnusedKeyInspectionTest"`
Expected: FAIL — `RnStyleUnusedKeyInspection` does not exist.

- [ ] **Step 3: Write the inspection**

`src/main/kotlin/com/webdx/rnstyles/RnStyleUnusedKeyInspection.kt`:
```kotlin
package com.webdx.rnstyles

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.webdx.cssmodules.CssModules

/**
 * Greys a key declared in a `StyleSheet.create({...})` object that is never referenced
 * (`<binding>.<key>` or via destructuring) within its scope — the containing file for an
 * inline object, the importer files for an exported one.
 */
class RnStyleUnusedKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        if (!CssModules.isJsLikeFileName(file.name)) return PsiElementVisitor.EMPTY_VISITOR

        val localObjects = RnStyles.fileStyleSheets(file).values.toSet()
        if (localObjects.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR

        val usedByObj = HashMap<JSObjectLiteralExpression, Set<String>>()
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is JSProperty) return
                val obj = element.parent as? JSObjectLiteralExpression ?: return
                if (obj !in localObjects) return
                val name = element.name ?: return
                val used = usedByObj.getOrPut(obj) { RnStyles.collectUsedKeys(obj) }
                if (name !in used) {
                    val anchor = element.nameIdentifier ?: element
                    holder.registerProblem(
                        anchor,
                        "Style '$name' is not used",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`**

Right after the `RnStyleUnknownKey*` registrations added in Task 6, add:
```xml
        <localInspection language="TypeScript JSX"
            shortName="RnStyleUnusedKeyTsx"
            displayName="Unused React Native style key"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.rnstyles.RnStyleUnusedKeyInspection"/>
        <localInspection language="TypeScript"
            shortName="RnStyleUnusedKeyTs"
            displayName="Unused React Native style key"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.rnstyles.RnStyleUnusedKeyInspection"/>
        <localInspection language="JavaScript"
            shortName="RnStyleUnusedKeyJs"
            displayName="Unused React Native style key"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.rnstyles.RnStyleUnusedKeyInspection"/>
        <localInspection language="ECMAScript 6"
            shortName="RnStyleUnusedKeyEs6"
            displayName="Unused React Native style key"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.rnstyles.RnStyleUnusedKeyInspection"/>
        <localInspection language="JSX Harmony"
            shortName="RnStyleUnusedKeyJsx"
            displayName="Unused React Native style key"
            groupName="JavaScript" enabledByDefault="true" level="WARNING"
            implementationClass="com.webdx.rnstyles.RnStyleUnusedKeyInspection"/>
```

- [ ] **Step 5: Run test to verify it passes**

Run: same as Step 2.
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/webdx/rnstyles/RnStyleUnusedKeyInspection.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/webdx/rnstyles/RnStyleUnusedKeyInspectionTest.kt
git commit -m "feat(rnstyles): unused style key inspection"
```

---

## Task 8: Scoped Find Usages on a style key

**Files:**
- Create: `src/main/kotlin/com/webdx/rnstyles/RnStyleFindUsagesHandlerFactory.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/webdx/rnstyles/RnStyleFindUsagesTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/webdx/rnstyles/RnStyleFindUsagesTest.kt`:
```kotlin
package com.webdx.rnstyles

import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Scoped Find Usages on a `StyleSheet.create` key. */
class RnStyleFindUsagesTest : BasePlatformTestCase() {

    private fun keyPropAtCaret(): JSProperty {
        val el = myFixture.file.findElementAt(myFixture.caretOffset)
        return PsiTreeUtil.getParentOfType(el, JSProperty::class.java, false) ?: error("no JSProperty at caret")
    }

    fun testReportsOnlyAccessesOfThatKeyInline() {
        myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ fo<caret>o: { flex: 1 }, bar: { flex: 1 } })\n" +
                "const a = styles.foo\nconst b = styles.foo\nconst c = styles.bar",
        )
        val usages = myFixture.findUsages(keyPropAtCaret())
        assertEquals("usages: ${usages.map { it.element?.text }}", 2, usages.size)
    }

    fun testDoesNotMatchSameNamedAccessOnOtherQualifier() {
        myFixture.configureByText(
            "Comp.tsx",
            "import { StyleSheet } from 'react-native'\n" +
                "const styles = StyleSheet.create({ fo<caret>o: { flex: 1 } })\n" +
                "const other = { foo: 1 }\nconst a = other.foo\nconst b = styles.foo",
        )
        val usages = myFixture.findUsages(keyPropAtCaret())
        assertEquals("usages: ${usages.map { it.element?.text }}", 1, usages.size)
    }

    fun testExportedKeyScopedToImporters() {
        myFixture.addFileToProject(
            "A.tsx",
            "import { styles } from './styles'\nconst x = styles.foo",
        )
        // B imports a DIFFERENT module but also writes styles.foo — must be excluded.
        myFixture.addFileToProject(
            "B.tsx",
            "import { styles } from './other'\nconst y = styles.foo",
        )
        myFixture.addFileToProject(
            "other.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ foo: { flex: 1 } })",
        )
        myFixture.configureByText(
            "styles.ts",
            "import { StyleSheet } from 'react-native'\nexport const styles = StyleSheet.create({ fo<caret>o: { flex: 1 } })",
        )
        val usages = myFixture.findUsages(keyPropAtCaret())
        assertEquals("usages: ${usages.map { it.element?.containingFile?.name }}", 1, usages.size)
        assertEquals("A.tsx", usages.first().element?.containingFile?.name)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew test --tests "com.webdx.rnstyles.RnStyleFindUsagesTest"`
Expected: FAIL — handler factory not registered, default TS find-usages returns the wrong count.

- [ ] **Step 3: Write the factory**

`src/main/kotlin/com/webdx/rnstyles/RnStyleFindUsagesHandlerFactory.kt`:
```kotlin
package com.webdx.rnstyles

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import com.webdx.cssmodules.CssModules

/**
 * Find Usages on a `StyleSheet.create` key property reports ONLY the `<binding>.<key>`
 * accesses (plus destructuring sites) within the key's scope — the containing file for an
 * inline object, the importer files for an exported one. Scans plain PSI leaves, with no
 * type resolution, so it does not match same-named members of unrelated objects.
 */
class RnStyleFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean = targetProperty(element) != null

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler {
        val prop = targetProperty(element)!!
        return object : FindUsagesHandler(prop) {
            override fun getFindUsagesOptions(dataContext: com.intellij.openapi.actionSystem.DataContext?): FindUsagesOptions =
                super.getFindUsagesOptions(dataContext)

            override fun processElementUsages(
                target: PsiElement,
                processor: Processor<in UsageInfo>,
                options: FindUsagesOptions,
            ): Boolean = ReadAction.compute<Boolean, RuntimeException> { processScoped(prop, processor) }
        }
    }

    /** The style-key JSProperty for [element]: a key property directly, or a usage resolving to one. */
    private fun targetProperty(element: PsiElement): JSProperty? {
        styleKeyProperty(element)?.let { return it }
        return RnStyles.resolveKeyProperty(element)
    }

    /** [element] is (or is inside) a JSProperty that is a top-level key of a StyleSheet object. */
    private fun styleKeyProperty(element: PsiElement): JSProperty? {
        val prop = element as? JSProperty
            ?: PsiTreeUtil.getParentOfType(element, JSProperty::class.java, false)
            ?: return null
        val obj = prop.parent as? JSObjectLiteralExpression ?: return null
        if (obj.parent !is JSArgumentList) return null
        val call = obj.parent.parent as? JSCallExpression ?: return null
        return if (RnStyles.isStyleSheetCreateCall(call)) prop else null
    }

    private fun processScoped(prop: JSProperty, processor: Processor<in UsageInfo>): Boolean {
        val key = prop.name ?: return true
        val obj = prop.parent as? JSObjectLiteralExpression ?: return true
        val definingFile = obj.containingFile?.originalFile ?: return true
        val binding = RnStyles.bindingNameOf(obj) ?: return true

        val scope = LinkedHashMap<PsiFile, MutableSet<String>>()
        scope.getOrPut(definingFile) { linkedSetOf() }.add(binding)
        if (RnStyles.isExported(obj)) {
            for ((f, locals) in RnStyles.importersForExport(definingFile, binding)) {
                scope.getOrPut(f) { linkedSetOf() }.addAll(locals)
            }
        }

        for ((file, bindings) in scope) {
            val leaves = PsiTreeUtil.collectElements(file) { el ->
                el.firstChild == null && el.textLength == key.length && el.text == key
            }
            for (leaf in leaves) {
                val dot = CssModules.prevMeaningfulLeaf(leaf) ?: continue
                if (dot.text != ".") continue
                val qualifier = CssModules.prevMeaningfulLeaf(dot) ?: continue
                if (qualifier.text !in bindings) continue
                if (!processor.process(UsageInfo(leaf))) return false
            }
        }
        return true
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`**

In the first `<extensions defaultExtensionNs="com.intellij">` block, next to the existing `CssModuleFindUsagesHandlerFactory`, add:
```xml
        <findUsagesHandlerFactory
            order="first"
            implementation="com.webdx.rnstyles.RnStyleFindUsagesHandlerFactory"/>
```

- [ ] **Step 5: Run test to verify it passes**

Run: same as Step 2.
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/webdx/rnstyles/RnStyleFindUsagesHandlerFactory.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/webdx/rnstyles/RnStyleFindUsagesTest.kt
git commit -m "feat(rnstyles): scoped find usages on a style key"
```

---

## Task 9: Wire go-to into the existing `GotoDeclaration` action

**Files:**
- Modify: `src/main/kotlin/com/webdx/cssmodules/CssModuleGotoDeclarationAction.kt`

> No new test: the overriding `GotoDeclaration` action is not unit-testable in the light
> fixture (same as the CSS action). The resolution it calls — `RnStyles.resolveKeyProperty`
> — is already fully covered by `RnStyleGotoDeclarationTest` (Task 4). The action wiring is
> verified manually in the IDE (see Task 10's manual-check note).

- [ ] **Step 1: Add the RN branch**

In `CssModuleGotoDeclarationAction.actionPerformed`, replace the existing inner `if` block:
```kotlin
                if (element != null && CssModuleClassNavigation.isMemberAccessLeaf(element)) {
                    val target = CssModuleClassNavigation.resolveTarget(element)
                    log.warn(
                        "[CSS-GOTOACTION] '${element.text}' -> ${target?.containingFile?.name ?: "null (delegating)"}",
                    )
                    if (target != null) {
                        PsiNavigateUtil.navigate(target)
                        return
                    }
                }
```
with:
```kotlin
                if (element != null) {
                    if (CssModuleClassNavigation.isMemberAccessLeaf(element)) {
                        val target = CssModuleClassNavigation.resolveTarget(element)
                        log.warn(
                            "[CSS-GOTOACTION] '${element.text}' -> ${target?.containingFile?.name ?: "null (delegating)"}",
                        )
                        if (target != null) {
                            PsiNavigateUtil.navigate(target)
                            return
                        }
                    }
                    // React Native StyleSheet styles: styles.<key> or a destructured local.
                    val rnTarget = com.webdx.rnstyles.RnStyles.resolveKeyProperty(element)
                    if (rnTarget != null) {
                        PsiNavigateUtil.navigate(rnTarget)
                        return
                    }
                }
```

- [ ] **Step 2: Run the full suite to verify nothing broke**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test`
Expected: PASS — all prior CSS/i18n tests plus the new `rnstyles` tests green.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/webdx/cssmodules/CssModuleGotoDeclarationAction.kt
git commit -m "feat(rnstyles): go-to styles.<key> via the GotoDeclaration action override"
```

---

## Task 10: Version bump, docs, full verification

**Files:**
- Modify: `build.gradle.kts` (version)
- Modify: `CHANGELOG.md`
- Modify: `README.md`

- [ ] **Step 1: Bump the plugin version**

In `build.gradle.kts`, change the `version` line from `1.6.0` to `1.7.0`.
(Find it with: `grep -n "version" build.gradle.kts` — it is the top-level `version = "1.6.0"`.)

- [ ] **Step 2: Add a CHANGELOG entry**

At the top of `CHANGELOG.md`, directly under the intro paragraphs (above `## 1.6.0 — 2026-06-10`), insert:
```markdown
## 1.7.0 — 2026-06-13
- **New: React Native `StyleSheet.create` support** (`com.webdx.rnstyles`), source-resolved
  like the CSS-module features. On `styles.<key>` (and on a `const { key } = styles`
  destructured local): go-to the single key declaration, scoped Find Usages (only the real
  `<binding>.<key>` accesses, not every same-named member), an "unknown style key" inspection,
  and an "unused style key" inspection (greys a `StyleSheet.create` key never referenced in its
  scope — the containing file for an inline object, the importer files for an exported one).
  Covers inline `const styles = StyleSheet.create({…})` and `export const styles = …` consumed
  via `import { styles } from './styles'`. Go-to is folded into the existing `GotoDeclaration`
  action override (the platform allows only one). No completion; no composition/spread or
  default-export styles (none exist in the target codebase).
  (`RnStyles`, `RnStyleUnknownKeyInspection`, `RnStyleUnusedKeyInspection`,
  `RnStyleFindUsagesHandlerFactory`.)
```

- [ ] **Step 3: Add a README section**

In `README.md`, in the intro "Two feature areas" list, append a third bullet after the i18n one:
```markdown
- **React Native `StyleSheet.create`** — on `styles.<key>` (and `const { key } = styles`):
  go-to the key declaration, scoped Find Usages, unknown-key and unused-key inspections.
  Source-resolved (no TS service); covers inline and exported+imported style objects.
```
And add a feature subsection after the i18n features block (after feature 11), before the
`---` that precedes "## Architecture / where each thing lives":
```markdown
### React Native StyleSheet styles (`com.webdx.rnstyles`)

A "StyleSheet object" is a `StyleSheet.create({ … })` call; its top-level object-literal
properties are the style keys. All features resolve from source PSI (no TS service), so they
behave correctly where the service treats `styles.<key>` as an untyped member.

12. **Go-to on `styles.<key>`** → the single key property in the `StyleSheet.create` object.
    Works for inline (`const styles = …`), imported (`import { styles } from './styles'`),
    aliased imports, and a destructured local (`const { title } = styles` → `title`). Folded
    into the `GotoDeclaration` action override (only one such override is allowed).
    → `RnStyles.resolveKeyProperty`, `CssModuleGotoDeclarationAction`

13. **Scoped Find Usages on a style key.** Lists only the `<binding>.<key>` accesses in the
    scope (the containing file for an inline object; the importer files for an exported one),
    instead of every same-named member in the project.
    → `RnStyleFindUsagesHandlerFactory`

14. **"Unknown style key" inspection.** `styles.doesNotExist` is flagged when `styles`
    resolves to a `StyleSheet.create` object and the key is absent. Only fires on a resolved
    StyleSheet binding, so unrelated member access is never redlined.
    → `RnStyleUnknownKeyInspection`

15. **"Unused style key" inspection.** A key never referenced (`<binding>.<key>` or via
    destructuring) in its scope is greyed as unused.
    → `RnStyleUnusedKeyInspection`
```

- [ ] **Step 4: Run the full suite one final time**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test`
Expected: PASS — full green (all existing tests + the 6 new `rnstyles` test classes).

- [ ] **Step 5: Manual IDE check (go-to action) — note for the implementer**

The overriding `GotoDeclaration` action can only be exercised in a real IDE. After
`./install-to-webstorm.sh` and restart, in `react-native-musescore` open
`src/App/Courses/Components/about/About.tsx` and verify: Cmd+B / Cmd+Click on `styles.title`
and on the destructured `title` (`const { title } = styles`) both land on the `title:` key in
`about/styles.tsx`; `boxRadius` in `src/modules/search/elements/Radio/styles.ts` is greyed as
unused. (Document the outcome; do not block the commit on it.)

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts CHANGELOG.md README.md
git commit -m "docs: rnstyles 1.7.0 — README + CHANGELOG, version bump"
```

---

## Self-review notes (already reconciled)

- **Spec coverage:** F1 go-to → Tasks 4 + 9; F2 find-usages → Task 8; F3 unknown → Task 6;
  F4 unused → Task 7; destructuring (nav + use-counting) → Tasks 4, 5, 7; inline + exported
  coverage → Tasks 5–8 tests. Helper surface (`fileStyleSheets`, `styleKeys`, `keyProperty`,
  `resolveStyleSheetForBinding`, `importersForExport`, `collectUsedKeys`, `resolveKeyProperty`)
  → Tasks 2–5.
- **Type consistency:** the helper names/signatures introduced in Tasks 1–5 are the exact ones
  consumed in Tasks 6–9 (`RnStyles.bindingsInFile`, `RnStyles.styleKeys`, `RnStyles.collectUsedKeys`,
  `RnStyles.isExported`, `RnStyles.importersForExport`, `RnStyles.resolveKeyProperty`,
  `RnStyles.isStyleSheetCreateCall`, `RnStyles.bindingNameOf`).
- **Known v1 limitations (per approved spec):** Find Usages enumerates `<binding>.<key>` accesses
  (and resolves from a destructured local to the key), but does not enumerate every downstream use
  of a destructured local — matching bare identifiers without type resolution risks false positives.
  Computed access `styles['x']`, spread/composition, and `export default StyleSheet.create(...)` are
  out of scope (absent from the target codebase).
```
