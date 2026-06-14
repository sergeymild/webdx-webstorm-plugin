package com.webdx.rnstyles

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.webdx.cssmodules.CssModules

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

    /**
     * Parse the inside of destructuring `const { … } = x` braces -> localName -> sourceKeyName.
     * Handles shorthand, `key: local` rename, default values (`x = 5`), and skips `...rest`.
     * Known limitation: TypeScript typed-with-default patterns (`x: Type = v`) are not supported —
     * they do not occur in RN `StyleSheet` destructuring (keys are plain identifiers).
     */
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

    // ---- detection ----

    fun isStyleSheetCreateCall(call: JSCallExpression): Boolean =
        call.methodExpression?.text?.trim() == "StyleSheet.create"

    /** The first-argument object literal of a `StyleSheet.create({...})` call, else null. */
    fun styleSheetObjectOf(call: JSCallExpression): JSObjectLiteralExpression? {
        if (!isStyleSheetCreateCall(call)) return null
        return call.arguments.firstOrNull() as? JSObjectLiteralExpression
    }

    /** Top-level property names of a StyleSheet object (computed/spread/unnamed skipped; duplicates deduped, first kept). */
    fun styleKeys(obj: JSObjectLiteralExpression): List<String> =
        obj.properties.mapNotNull { it.name }.distinct()

    /** The [JSProperty] declaring style key [name] in [obj], or null if absent. */
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
            val name = bindingNameOf(obj) ?: continue
            out.putIfAbsent(name, obj)
        }
        return out
    }

    internal val NAMED_IMPORT = Regex("""import\s*\{([^}]*)\}\s*from\s*['"]([^'"]+)['"]""")

    /** Resolve a JS/TS import [path] (extensionless allowed) to a VirtualFile. */
    fun resolveJsImport(fromDir: VirtualFile, project: Project, path: String): VirtualFile? {
        CssModules.resolveImportPath(fromDir, project, path)?.let { if (!it.isDirectory) return it }
        for (ext in CssModules.JS_EXTS) CssModules.resolveImportPath(fromDir, project, "$path.$ext")?.let { return it }
        for (ext in CssModules.JS_EXTS) CssModules.resolveImportPath(fromDir, project, "$path/index.$ext")?.let { return it }
        return null
    }

    /**
     * Resolve qualifier [binding] used in [jsFile] to its StyleSheet object:
     * any same-file `<binding> = StyleSheet.create(...)` first, else a named import
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

    private val DESTRUCTURE = Regex("""(?:const|let|var)\s*\{([^}]*)\}\s*(?::[^=]*)?\s*=\s*([A-Za-z_$][\w$]*)""")

    /**
     * local name -> (StyleSheet object, source key) for `const { … } = <styles binding>` in [file].
     * TODO(v2): file-wide and scope-blind — a same-named local in an unrelated scope resolves here too.
     */
    fun destructuredKeys(file: PsiFile): Map<String, Pair<JSObjectLiteralExpression, String>> {
        val real = file.originalFile
        val out = LinkedHashMap<String, Pair<JSObjectLiteralExpression, String>>()
        for (m in DESTRUCTURE.findAll(real.text)) {
            val obj = resolveStyleSheetForBinding(real, m.groupValues[2]) ?: continue
            for ((local, key) in parseDestructuredEntries(m.groupValues[1])) out.putIfAbsent(local, obj to key)
        }
        return out
    }

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

    /** Exported `StyleSheet.create` bindings in [file] (binding name -> object). */
    fun exportedStyleSheetBindings(file: PsiFile): Map<String, JSObjectLiteralExpression> =
        fileStyleSheets(file).filterValues { isExported(it) }

    /** True if the StyleSheet object is declared with `export` (so it can be consumed elsewhere). */
    fun isExported(obj: JSObjectLiteralExpression): Boolean {
        val stmt = PsiTreeUtil.getParentOfType(obj, JSVarStatement::class.java) ?: return false
        return stmt.attributeList?.text?.contains("export") == true
    }

    /** Files importing [exportName] from [stylesFile], each mapped to the local binding name(s) used. */
    fun importersForExport(stylesFile: PsiFile, exportName: String): Map<PsiFile, Set<String>> {
        val project = stylesFile.project
        val target = stylesFile.virtualFile ?: return emptyMap()
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val out = LinkedHashMap<PsiFile, MutableSet<String>>()
        for (ext in CssModules.JS_EXTS) {
            for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                if (vf == target) continue
                val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: continue
                if (!Regex("""\b${Regex.escape(exportName)}\b""").containsMatchIn(text)) continue
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
        // Precondition: callers pass an obj from fileStyleSheets, which only yields objects
        // with a binding name — so this never silently empties the used-set for a real sheet.
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
                val dotBeforeQ = CssModules.prevMeaningfulLeaf(q)
                if (dotBeforeQ != null && dotBeforeQ.text == ".") return@forEach // chained: x.styles.key
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

    /**
     * Resolve the identifier leaf [element] to the StyleSheet key property it refers to:
     *  - a `<binding>.<key>` member access, or
     *  - a local destructured from a StyleSheet object (incl. the destructuring site).
     * Resolves against `originalFile` (navigation hands a non-physical copy — CSS gotcha #8).
     */
    fun resolveKeyProperty(element: PsiElement): JSProperty? {
        if (element.firstChild != null) return null // leaves only
        val name = element.text ?: return null // getText() is a platform type; some leaves return null
        if (name.isEmpty() || !name.first().isJavaIdentifierStart()) return null
        val file = element.containingFile?.originalFile ?: return null
        if (!CssModules.isJsLikeFileName(file.name)) return null

        val dot = CssModules.prevMeaningfulLeaf(element)
        if (dot != null && dot.text == ".") { // Case A: <binding>.<name>
            val qualifier = CssModules.prevMeaningfulLeaf(dot) ?: return null
            val q = qualifier.text ?: return null
            // Skip non-identifier qualifiers (e.g. `getStyles().x`, `arr[i].x`) before the
            // file-wide PSI/text scans in resolveStyleSheetForBinding.
            if (q.isEmpty() || !q.first().isJavaIdentifierStart()) return null
            val obj = resolveStyleSheetForBinding(file, q) ?: return null
            return keyProperty(obj, name)
        }

        // Case B: a local destructured from a StyleSheet object (incl. the destructuring site)
        val (obj, key) = destructuredKeys(file)[name] ?: return null
        return keyProperty(obj, key)
    }
}
