package com.webdx.cssmodules

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.css.CssClass
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil

/** Minimal view of a tsconfig's path aliasing: optional baseUrl + `paths` mappings (first target only). */
internal data class TsconfigAliases(val baseUrl: String?, val paths: Map<String, String>)

/** Shared CSS-module helpers used by completion, the unused-class inspection, etc. */
internal object CssModules {

    private val SCSS_IMPORT = Regex("""@(?:import|use|forward)\b([^;{}\n]*)""")
    private val QUOTED = Regex("""['"]([^'"]+)['"]""")

    /** `@extend .className` — a class-selector extend. The bare class name is group 1. */
    private val EXTEND_CLASS = Regex("""@extend\s+\.([\w-]+)""")

    /**
     * Name-token ranges (the bare class name, no leading dot) of every `@extend .name` in [text].
     * The platform parses the `.name` inside `@extend` as a [CssClass] PSI node identical to a
     * real selector declaration; these ranges let callers tell the two apart so an `@extend` is
     * treated as a class REFERENCE, not a declaration.
     */
    fun extendClassRefRanges(text: String): List<IntRange> =
        EXTEND_CLASS.findAll(text).map { it.groups[1]!!.range }.toList()

    /** True if [range] overlaps any range in [ranges] (inclusive `IntRange` ends). */
    fun rangeOverlapsAny(range: TextRange?, ranges: List<IntRange>): Boolean {
        if (range == null) return false
        return ranges.any { range.startOffset <= it.last && it.first < range.endOffset }
    }

    /** All paths referenced by `@import`/`@use`/`@forward` in SCSS [text], in order. */
    fun scssImportPaths(text: String): List<String> =
        SCSS_IMPORT.findAll(text)
            .flatMap { m -> QUOTED.findAll(m.groupValues[1]).map { it.groupValues[1] } }
            .toList()

    private val TS_BASE_URL = Regex(""""baseUrl"\s*:\s*"([^"]+)"""")
    private val TS_PATHS_BLOCK = Regex(""""paths"\s*:\s*\{""")
    private val TS_PATH_ENTRY = Regex(""""([^"]+)"\s*:\s*\[\s*"([^"]+)"""")

    /** Parse `compilerOptions.baseUrl` and `compilerOptions.paths` (first target per key) from tsconfig text. */
    fun tsconfigAliases(text: String): TsconfigAliases {
        val baseUrl = TS_BASE_URL.find(text)?.groupValues?.get(1)
        val blockStart = TS_PATHS_BLOCK.find(text)?.range?.last ?: return TsconfigAliases(baseUrl, emptyMap())
        // Take the balanced `{ ... }` that opens at blockStart. Not string-aware:
        // assumes no `{`/`}` inside path keys/values (true for glob aliases like `@/*`).
        var depth = 0
        var end = blockStart
        for (i in blockStart until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        val block = text.substring(blockStart, end + 1)
        val paths = TS_PATH_ENTRY.findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
        return TsconfigAliases(baseUrl, paths)
    }

    fun isModuleFileName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".module.css") ||
            n.endsWith(".module.scss") ||
            n.endsWith(".module.sass") ||
            n.endsWith(".module.less")
    }

    fun isJsLikeFileName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".tsx") || n.endsWith(".ts") ||
            n.endsWith(".jsx") || n.endsWith(".js") ||
            n.endsWith(".mts") || n.endsWith(".cts") ||
            n.endsWith(".mjs") || n.endsWith(".cjs")
    }

    /**
     * All class names DECLARED in a CSS-module file (deduped, dot stripped). A `.name` sitting
     * inside an `@extend .name` is a reference to a class declared elsewhere, not a declaration,
     * so it is excluded (the platform parses it as a [CssClass] indistinguishable from a real one).
     */
    fun collectClassNames(moduleFile: PsiFile): List<String> {
        val extendRanges = extendClassRefRanges(moduleFile.text)
        return (PsiTreeUtil.collectElementsOfType(moduleFile, CssClass::class.java)
            .filterNot { rangeOverlapsAny(it.textRange, extendRanges) }
            .mapNotNull { it.name?.removePrefix(".")?.takeIf(String::isNotEmpty) } +
            BamSelectors.bamClassDeclarations(moduleFile).keys)
            .distinct()
    }

    /** The CSS-module files directly imported by [scssFile] via @import/@use/@forward. */
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
        collectClassOrigins(moduleFile).keys.toList()

    /**
     * Each class reachable from [moduleFile] mapped to the file that declares it.
     * Walks own classes first, then imported ones, so on a name clash the module's
     * OWN file wins (it's the effective declaration by the Sass `@import` cascade).
     */
    fun collectClassOrigins(moduleFile: PsiFile): Map<String, PsiFile> =
        CachedValuesManager.getCachedValue(moduleFile) {
            val out = LinkedHashMap<String, PsiFile>()
            collectOriginsInto(moduleFile, out, HashSet())
            CachedValueProvider.Result.create(out, PsiModificationTracker.MODIFICATION_COUNT)
        }

    private fun collectOriginsInto(
        file: PsiFile,
        out: MutableMap<String, PsiFile>,
        visited: MutableSet<VirtualFile>,
    ) {
        val vf = file.virtualFile ?: return
        if (!visited.add(vf)) return
        for (name in collectClassNames(file)) out.putIfAbsent(name, file)
        for (imported in directModuleImports(file)) collectOriginsInto(imported, out, visited)
    }

    /**
     * Classes contributed by [moduleFile]'s transitively imported modules, mapped to
     * the file that declares each — EXCLUDING [moduleFile]'s own classes. A name that
     * appears here AND is declared in [moduleFile] is a local override of an
     * `@import`-ed class. Cached on [moduleFile].
     */
    fun importedClassOrigins(moduleFile: PsiFile): Map<String, PsiFile> =
        CachedValuesManager.getCachedValue(moduleFile) {
            val out = LinkedHashMap<String, PsiFile>()
            val visited = HashSet<VirtualFile>()
            moduleFile.virtualFile?.let { visited.add(it) } // don't include the file's own classes
            for (imported in directModuleImports(moduleFile)) collectOriginsInto(imported, out, visited)
            CachedValueProvider.Result.create<Map<String, PsiFile>>(out, PsiModificationTracker.MODIFICATION_COUNT)
        }

    // --- SCSS symbol definitions (mixins / functions / variables / placeholders) ---

    private val SCSS_MIXIN = Regex("""@mixin\s+([\w-]+)""")
    private val SCSS_FUNCTION = Regex("""@function\s+([\w-]+)""")
    private val SCSS_VARIABLE = Regex("""(?m)^\s*\$([\w-]+)\s*:""")
    private val SCSS_PLACEHOLDER = Regex("""%([\w-]+)\s*[{,]""")

    /** Names defined in SCSS [text]: `@mixin`/`@function`/top-level `$var`/`%placeholder`. */
    fun scssDefinedSymbols(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (re in listOf(SCSS_MIXIN, SCSS_FUNCTION, SCSS_VARIABLE, SCSS_PLACEHOLDER)) {
            re.findAll(text).forEach { out.add(it.groupValues[1]) }
        }
        return out
    }

    /**
     * Project-wide index of SCSS symbol name -> the file that defines it (first wins).
     * Covers `.scss`/`.sass` files. Cached on [project] + PSI modification count.
     */
    fun scssSymbolIndex(project: Project): Map<String, VirtualFile> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val index = HashMap<String, VirtualFile>()
            for (ext in listOf("scss", "sass")) {
                for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                    val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: continue
                    for (name in scssDefinedSymbols(text)) index.putIfAbsent(name, vf)
                }
            }
            CachedValueProvider.Result.create<Map<String, VirtualFile>>(index, PsiModificationTracker.MODIFICATION_COUNT)
        }

    /** True if [scssFile] already `@import`/`@use`/`@forward`s [target]. */
    fun importsTarget(scssFile: PsiFile, target: VirtualFile): Boolean {
        val dir = scssFile.virtualFile?.parent ?: return false
        val project = scssFile.project
        return scssImportPaths(scssFile.text).any { resolveImportPath(dir, project, it) == target }
    }

    /**
     * Build an import specifier for [targetFile] using a tsconfig `@/`-style alias when
     * one matches (e.g. `@/styles/mixins.scss`); falls back to a relative path from
     * [fromDir]. Returns null only if nothing can be built.
     */
    fun importSpecifierFor(project: Project, fromDir: VirtualFile, targetFile: VirtualFile): String? {
        aliasSpecifierFor(fromDir, targetFile)?.let { return it }
        return relativeSpecifier(fromDir, targetFile)
    }

    private fun aliasSpecifierFor(fromDir: VirtualFile, targetFile: VirtualFile): String? {
        val tsconfig = findTsconfig(fromDir) ?: return null
        val tsDir = tsconfig.parent ?: return null
        val text = runCatching { VfsUtilCore.loadText(tsconfig) }.getOrNull() ?: return null
        val cfg = tsconfigAliases(text)
        val baseDir = cfg.baseUrl?.let { resolveRelative(tsDir, it) } ?: tsDir
        for ((key, template) in cfg.paths) {
            if (!key.endsWith("/*") || !template.contains("*")) continue
            val templateDirPath = template.substringBefore("*")
            val templateBase = resolveRelative(baseDir, templateDirPath) ?: continue
            val remainder = relativePathBetween(templateBase, targetFile) ?: continue
            return key.dropLast(1) + remainder // "@/*" -> "@/" + remainder
        }
        return null
    }

    private fun relativeSpecifier(fromDir: VirtualFile, targetFile: VirtualFile): String? {
        val rel = relativePathBetween(fromDir, targetFile)
        if (rel != null) return "./$rel"
        // target is not under fromDir: climb up
        val up = VfsUtilCore.findRelativePath(fromDir, targetFile, '/') ?: return null
        return if (up.startsWith(".")) up else "./$up"
    }

    /** Path of [target] relative to [base] dir, or null if [target] is not under [base]. */
    private fun relativePathBetween(base: VirtualFile, target: VirtualFile): String? =
        VfsUtilCore.getRelativePath(target, base, '/')

    private val CSS_EXTS = listOf("scss", "sass", "less", "css")

    /** JS/TS source extensions, used for extensionless import resolution. */
    val JS_EXTS = listOf("ts", "tsx", "js", "jsx", "mts", "cts", "mjs", "cjs")

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

    /** [moduleFile] plus every CSS module that transitively imports it. */
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

    /**
     * Find a CSS module imported in [jsFile] under the local name [binding]
     * (`import <binding> from './x.module.scss'`) and return its PsiFile.
     */
    fun resolveModuleForBinding(jsFile: PsiFile, binding: String): PsiFile? {
        val dir = jsFile.virtualFile?.parent ?: return null
        val regex = Regex("""import\s+${Regex.escape(binding)}\s+from\s+['"]([^'"]+)['"]""")
        val path = regex.find(jsFile.text)?.groupValues?.get(1) ?: return null
        if (!isModuleFileName(path)) return null
        // resolveImportPath handles both relative and `@/` tsconfig-alias imports.
        val vf = resolveImportPath(dir, jsFile.project, path) ?: return null
        return PsiManager.getInstance(jsFile.project).findFile(vf)
    }

    /**
     * Every CSS-module import in [jsFile] as `localBinding -> {classNames}`.
     * e.g. `import styles from './X.module.scss'` -> "styles" -> {mobileWrapper, ...}.
     */
    fun cssModuleBindings(jsFile: PsiFile): Map<String, Set<String>> {
        val dir = jsFile.virtualFile?.parent ?: return emptyMap()
        val psiManager = PsiManager.getInstance(jsFile.project)
        val result = HashMap<String, Set<String>>()
        Regex("""import\s+(\w+)\s+from\s+['"]([^'"]+)['"]""").findAll(jsFile.text).forEach { m ->
            val binding = m.groupValues[1]
            val path = m.groupValues[2]
            if (!isModuleFileName(path)) return@forEach
            val vf = resolveRelative(dir, path) ?: return@forEach
            val psi = psiManager.findFile(vf) ?: return@forEach
            result[binding] = collectAllClassNames(psi).toSet()
        }
        return result
    }

    /** Files importing [moduleFile], each mapped to the local binding name(s) used. */
    fun findImporters(moduleFile: PsiFile): Map<PsiFile, Set<String>> {
        val project = moduleFile.project
        val map = LinkedHashMap<PsiFile, MutableSet<String>>()
        ReferencesSearch
            .search(moduleFile, GlobalSearchScope.projectScope(project))
            .forEach { ref ->
                val f = ref.element.containingFile ?: return@forEach
                val set = map.getOrPut(f) { linkedSetOf() }
                importBindingName(ref.element)?.let { set.add(it) }
            }
        return map
    }

    /**
     * Class names of [moduleFile] actually referenced as `<binding>.<name>` in any JS
     * file that imports [moduleFile] OR any CSS module that transitively imports it
     * (Sass inlines those classes, so they're exported on the consumer's `styles`).
     * Returns null when nothing consumes the module from JS (directly or via an
     * `@import` chain) — the caller can't tell what's used, so it should not flag anything.
     */
    fun collectUsedClassNames(moduleFile: PsiFile): Set<String>? {
        val ownClasses = collectClassNames(moduleFile).toSet()
        if (ownClasses.isEmpty()) return null
        val psiManager = PsiManager.getInstance(moduleFile.project)
        val used = HashSet<String>()
        var hasJsConsumer = false
        for (vf in modulesTransitivelyImporting(moduleFile)) {
            val consumer = psiManager.findFile(vf) ?: continue
            // SCSS `@extend .name` in a module that (transitively) imports moduleFile consumes
            // that class — it inlines moduleFile's declaration. Counts as a use even though it is
            // never referenced as `styles.<name>` from JS.
            for (m in EXTEND_CLASS.findAll(consumer.text)) {
                val extended = m.groupValues[1]
                if (extended in ownClasses) used.add(extended)
            }
            for ((file, bindings) in findImporters(consumer)) {
                if (!isJsLikeFileName(file.name)) continue // only JS files reference styles.<class>
                hasJsConsumer = true
                PsiTreeUtil.collectElements(file) { it.firstChild == null }
                    .forEach { leaf ->
                        // dot access: `<binding>.<member>`
                        if (leaf.text == ".") {
                            val qualifier = prevMeaningfulLeaf(leaf) ?: return@forEach
                            if (qualifier.text !in bindings) return@forEach
                            val member = nextMeaningfulLeaf(leaf) ?: return@forEach
                            if (member.text in ownClasses) used.add(member.text)
                            return@forEach
                        }
                        // bracket access `<binding>[...]`: a static string literal is a use of
                        // that one class (handled below); a computed/dynamic key (`styles[variant]`
                        // / `styles[`a${x}`]`) can't be resolved, so EVERY class of the module is
                        // considered used (never falsely flagged — mirrors the RN-styles fix).
                        if (leaf.text == "[") {
                            val qualifier = dynamicBracketQualifier(leaf) ?: return@forEach
                            if (qualifier.text in bindings) used.addAll(ownClasses)
                            return@forEach
                        }
                        // static bracket access: `<binding>['member']` (needed for `--`-modifier
                        // names, which are not valid JS identifiers for dot access)
                        val (qualifier, member) = bracketMemberAccess(leaf) ?: return@forEach
                        if (qualifier in bindings && member in ownClasses) used.add(member)
                    }
            }
        }
        return if (hasJsConsumer) used else null
    }

    fun prevMeaningfulLeaf(el: PsiElement): PsiElement? {
        var p = PsiTreeUtil.prevLeaf(el)
        while (p != null && (p is PsiWhiteSpace || p is PsiComment)) p = PsiTreeUtil.prevLeaf(p)
        return p
    }

    fun nextMeaningfulLeaf(el: PsiElement): PsiElement? {
        var p = PsiTreeUtil.nextLeaf(el)
        while (p != null && (p is PsiWhiteSpace || p is PsiComment)) p = PsiTreeUtil.nextLeaf(p)
        return p
    }

    /**
     * If [stringLeaf] is the key of a `<qualifier>['key']` bracket access, return
     * (qualifierText, key) with the surrounding quotes stripped; else null. Only static
     * single/double-quoted keys are recognised (a `styles[variable]` or template literal
     * is dynamic and yields null). Used so `--`-modifier class names — which are not valid
     * JS identifiers for dot access — are seen by the usage/unknown/find-usages scanners.
     */
    fun bracketMemberAccess(stringLeaf: PsiElement): Pair<String, String>? {
        if (stringLeaf.firstChild != null) return null // leaves only
        val key = stripQuotes(stringLeaf.text) ?: return null
        val open = prevMeaningfulLeaf(stringLeaf) ?: return null
        if (open.text != "[") return null
        val qualifier = prevMeaningfulLeaf(open) ?: return null
        val q = qualifier.text
        if (q.isEmpty() || !q.first().isJavaIdentifierStart()) return null
        return q to key
    }

    /**
     * If [openBracket] (a leaf whose text is `[`) opens a DYNAMIC bracket access
     * `<qualifier>[<computed>]` — the key is NOT a static single/double-quoted string
     * (`styles[variant]`, `styles[`a${x}`]`) — return the qualifier leaf; else null.
     * A static-string key (`styles['x']`) yields null: those resolve to one class via
     * [bracketMemberAccess]. Chained access (`x.styles[...]`) yields null so we don't
     * mistake a property named like an import binding for the binding itself.
     */
    fun dynamicBracketQualifier(openBracket: PsiElement): PsiElement? {
        if (openBracket.firstChild != null || openBracket.text != "[") return null
        val qualifier = prevMeaningfulLeaf(openBracket) ?: return null
        val q = qualifier.text
        if (q.isEmpty() || !q.first().isJavaIdentifierStart()) return null
        val dotBeforeQ = prevMeaningfulLeaf(qualifier)
        if (dotBeforeQ != null && dotBeforeQ.text == ".") return null // chained: x.styles[...]
        val inside = nextMeaningfulLeaf(openBracket) ?: return null
        if (stripQuotes(inside.text) != null) return null // static string key -> not dynamic
        return qualifier
    }

    private fun stripQuotes(s: String): String? {
        if (s.length < 2) return null
        val quote = s.first()
        if ((quote == '\'' || quote == '"') && s.last() == quote) return s.substring(1, s.length - 1)
        return null
    }

    /**
     * Resolve a SCSS `@import`/`@use`/`@forward` [path] to a VirtualFile.
     * Relative (`.`/`..`/bare) resolve against [fromDir]; `@/`-style aliases resolve
     * via the nearest tsconfig's `paths`. Returns null if nothing resolves.
     */
    fun resolveImportPath(fromDir: VirtualFile, project: Project, path: String): VirtualFile? {
        if (path.startsWith(".")) return resolveRelative(fromDir, path)
        return resolveAlias(fromDir, path) ?: resolveRelative(fromDir, path)
    }

    private fun resolveAlias(fromDir: VirtualFile, path: String): VirtualFile? {
        val tsconfig = findTsconfig(fromDir) ?: return null
        val text = runCatching { VfsUtilCore.loadText(tsconfig) }.getOrNull() ?: return null
        val cfg = tsconfigAliases(text)
        val tsDir = tsconfig.parent ?: return null
        val baseDir = cfg.baseUrl
            ?.let { resolveRelative(tsDir, it) }
            ?: tsDir
        for ((key, template) in cfg.paths) {
            val mapped = applyAlias(key, template, path) ?: continue
            resolveRelative(baseDir, mapped)?.let { return it }
        }
        return null
    }

    // Glob alias (key ending with "/*") maps by wildcard prefix; exact key matches path literally.
    private fun applyAlias(key: String, template: String, path: String): String? {
        if (key.endsWith("/*")) {
            val prefix = key.dropLast(1) // "@/*" -> "@/"
            if (!path.startsWith(prefix)) return null
            val wildcard = path.substring(prefix.length)
            return template.replaceFirst("*", wildcard)
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

    private fun resolveRelative(from: VirtualFile, path: String): VirtualFile? {
        var cur: VirtualFile? = from
        for (part in path.split('/')) {
            cur = when (part) {
                "", "." -> cur
                ".." -> cur?.parent
                else -> cur?.findChild(part)
            }
            if (cur == null) return null
        }
        return cur
    }

    private fun importBindingName(refElement: PsiElement): String? {
        var cur: PsiElement? = refElement
        var depth = 0
        while (cur != null && depth < 12) {
            val text = cur.text
            if (text.startsWith("import")) {
                return Regex("""import\s+(\w+)""").find(text)?.groupValues?.get(1)
            }
            cur = cur.parent
            depth++
        }
        return null
    }
}
