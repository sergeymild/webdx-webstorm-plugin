package com.webdx.barrels

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.webdx.cssmodules.CssModules

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
    fun exportedNameAt(element: PsiElement): Pair<String, Boolean>? = TODO("Task 5")
    fun planFor(componentFile: PsiFile, name: String, isDefault: Boolean, project: Project): Plan? = TODO("Task 6")
}
