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
