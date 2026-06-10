package com.intch.cssmodules

import com.intellij.openapi.project.Project
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

    /** All class names declared in a CSS-module file (deduped, dot stripped). */
    fun collectClassNames(moduleFile: PsiFile): List<String> =
        PsiTreeUtil.collectElementsOfType(moduleFile, CssClass::class.java)
            .mapNotNull { it.name?.removePrefix(".")?.takeIf(String::isNotEmpty) }
            .distinct()

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
        val vf = resolveRelative(dir, path) ?: return null
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
