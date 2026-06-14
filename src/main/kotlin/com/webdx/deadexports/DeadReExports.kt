package com.webdx.deadexports

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * Reverse reachability over the ES6 re-export graph: a name re-exported by a module is
 * "dead" when no real (non-re-export) consumer reaches it, even through chains of
 * `export … from`. Leans on the IDE's module resolution (ReferencesSearch), so `@/`
 * path aliases and `require()` are handled the same way the editor resolves them.
 */
object DeadReExports {

    /** Sentinel standing for every name of a module (`export * from` / `import *` / `require(F)`). */
    const val STAR = "*"

    /**
     * Source-module names a re-export declaration forwards: the *source* name of each
     * specifier (the name as it exists in the source module), or [STAR] for `export *`.
     *
     * NOTE: in this SDK the source name is `getReferenceName()` (for `b as c`, the `b`);
     * `getDeclaredName()` is the publicly *exported* name (`c`), and `getName()` is null
     * when aliased — opposite to what the plan's API note assumed. Verified against the
     * bundled javascript-plugin.jar bytecode (ES6ImportExportSpecifierBase.getDeclaredName
     * returns the alias name when present, else getReferenceName).
     */
    fun reExportedSourceNames(decl: ES6ExportDeclaration): List<String> {
        if (decl.isExportAll) return listOf(STAR)
        return decl.exportSpecifiers.mapNotNull { it.referenceName }
    }

    sealed interface RefKind {
        /** A non-re-export use (import / require / dynamic import): keeps the name live. */
        object RealConsumer : RefKind
        /** A re-export that forwards the name onward; liveness depends on [decl]'s own consumers. */
        data class ReExportSite(val decl: ES6ExportDeclaration) : RefKind
    }

    /**
     * Classify one reference to a module file. Conservative: anything we cannot positively
     * identify as an `export … from` re-export is treated as a real consumer.
     */
    fun classify(refElement: PsiElement): RefKind {
        val decl = PsiTreeUtil.getParentOfType(refElement, ES6ImportExportDeclaration::class.java, false)
        if (decl is ES6ExportDeclaration && decl.isReExport) return RefKind.ReExportSite(decl)
        return RefKind.RealConsumer
    }

    /**
     * Stateful per-pass analyzer. Holds a memo so repeated `isLive` queries within one
     * inspection pass share work, and a visited set so re-export cycles terminate.
     */
    class Analyzer(private val project: Project) {
        private val scope = GlobalSearchScope.projectScope(project)
        private val memo = HashMap<Pair<String, String>, Boolean>()

        /** Is [name] (a name exported by [moduleFile]) reached by any real consumer? */
        fun isLive(moduleFile: PsiFile, name: String): Boolean =
            isLive(moduleFile, name, HashSet())

        private fun isLive(moduleFile: PsiFile, name: String, visited: MutableSet<Pair<String, String>>): Boolean {
            val origin = moduleFile.originalFile
            val key = (origin.virtualFile?.path ?: origin.name) to name
            memo[key]?.let { return it }
            if (!visited.add(key)) return false // cycle: this path contributes no new liveness

            var live = false
            for (ref in ReferencesSearch.search(origin, scope).findAll()) {
                when (val kind = classify(ref.element)) {
                    RefKind.RealConsumer -> { live = true; break }
                    is RefKind.ReExportSite -> {
                        val g = kind.decl.containingFile?.originalFile ?: continue
                        if (forwardsName(kind.decl, name)) {
                            for (forwarded in forwardedAs(kind.decl, name)) {
                                if (isLive(g, forwarded, visited)) { live = true; break }
                            }
                        }
                        if (live) break
                    }
                }
            }
            memo[key] = live
            return live
        }

        /** Does this re-export site forward our source [name] (directly or via `export *`)? */
        private fun forwardsName(decl: ES6ExportDeclaration, name: String): Boolean {
            if (decl.isExportAll) return true
            return decl.exportSpecifiers.any { it.referenceName == name }
        }

        /**
         * The name(s) under which [name] leaves [decl]: the publicly exported name
         * (`declaredName` in this SDK — the alias for `b as c`, else the source name),
         * or [name] itself for `export *`.
         */
        private fun forwardedAs(decl: ES6ExportDeclaration, name: String): List<String> {
            if (decl.isExportAll) return listOf(name)
            return decl.exportSpecifiers
                .filter { it.referenceName == name }
                .map { it.declaredName ?: name }
        }
    }
}
