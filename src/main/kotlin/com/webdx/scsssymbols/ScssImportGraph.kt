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
