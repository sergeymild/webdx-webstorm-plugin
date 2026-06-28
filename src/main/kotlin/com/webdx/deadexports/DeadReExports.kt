package com.webdx.deadexports

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6NamespaceImportExport
import com.intellij.lang.ecmascript6.resolve.ES6PsiUtil
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
     * The set of *source* names a [RefKind.RealConsumer] reference draws from the module,
     * or [STAR] when it consumes every name. Used at the consumer leaf of [Analyzer.isLive]
     * to grant liveness only to the names a consumer actually imports — so a barrel
     * `export { Live, Dead } from './x'` whose only importer is `import { Live }` keeps
     * `Live` live but lets `Dead` die.
     *
     * For an `ES6ImportDeclaration`:
     *  - `import * as ns` (a namespace import) → [STAR];
     *  - named imports contribute their SOURCE name (`specifier.referenceName`; for
     *    `import { Dead as D }` that is `Dead`, not the local alias `D`);
     *  - a default binding (`import X` / `import X, { … }`) contributes `"default"`;
     *  - a bare side-effect import `import './x'` contributes nothing (empty set).
     *
     * Any reference NOT inside an import (a `require(F)`, `require(F).x`, or dynamic
     * `import(F)`) is treated conservatively as consuming everything → [STAR]; we do not
     * narrow `require` member access.
     */
    fun consumedNames(refElement: PsiElement): Set<String> {
        val decl = PsiTreeUtil.getParentOfType(refElement, ES6ImportDeclaration::class.java, false)
            ?: return setOf(STAR)
        // `import * as ns` re-exposes the whole module namespace.
        if (PsiTreeUtil.findChildOfType(decl, ES6NamespaceImportExport::class.java) != null) {
            return setOf(STAR)
        }
        val names = decl.importSpecifiers.mapNotNullTo(HashSet()) { it.referenceName }
        if (decl.importedBindings.isNotEmpty()) names += "default"
        return names
    }

    /**
     * Stateful per-pass analyzer. Holds a memo so repeated `isLive` queries within one
     * inspection pass share work, and a visited set so re-export cycles terminate.
     */
    class Analyzer(private val project: Project) {
        private val scope = GlobalSearchScope.projectScope(project)
        private val memo = HashMap<Pair<String, String>, Boolean>()
        // Per-origin-file reference list cache: ReferencesSearch is a project-wide scan, so a
        // barrel with N specifiers would otherwise re-run the SAME search N times. Keyed on the
        // same path string used for the memo key. The verdict memo dedupes by (path, name); this
        // dedupes the underlying search by path.
        private val refsCache = HashMap<String, List<com.intellij.psi.PsiReference>>()
        // Memo for "does source module set <sourceKey> export <name>?" — resolveSymbolInModules is
        // a per-name module resolution, and the same names recur across a barrel's many consumers.
        private val exportsMemo = HashMap<Pair<String, String>, Boolean>()

        /**
         * Result of one recursive [isLive] computation: the liveness verdict plus whether
         * that verdict was (transitively) influenced by a cycle cutoff against an
         * in-progress ancestor. A `false` verdict that [hitCycle] only because the
         * visited-guard short-circuited an ancestor is *provisional* — it holds for this
         * traversal but is NOT a final answer, so it must not be memoized for later
         * top-level queries. A `true` verdict is always final regardless of [hitCycle].
         */
        private data class Result(val live: Boolean, val hitCycle: Boolean)

        /** Is [name] (a name exported by [moduleFile]) reached by any real consumer? */
        fun isLive(moduleFile: PsiFile, name: String): Boolean =
            isLive(moduleFile, name, HashSet()).live

        /**
         * Alias-safe liveness backstop. The file-based [isLive] walk searches references to a
         * MODULE FILE, and IntelliJ resolves those reverse references through a word index keyed
         * on the file's own name — so a consumer that imports through a tsconfig `paths` alias
         * (e.g. `import { X } from '@mu-native/ds'`), whose specifier text shares no word with the
         * target file's name, is never visited and the export looks dead even though it is used.
         *
         * Searching references to the exported SYMBOL instead finds those consumers: the imported
         * identifier carries the symbol's own name (so the word index reaches the consumer file)
         * and resolves — through any chain of `export … from` re-exports — back to [symbol].
         *
         * Returns true iff some reference to [symbol] is a real (non-re-export) consumer living in
         * a DIFFERENT file than [symbol]'s own. Same-file references are ignored to preserve the
         * "used only inside its own module = unused" rule that the file-based walk gives for free.
         * Re-export sites (`export { symbol } from …`) resolve to [symbol] too but are classified
         * out, so a symbol forwarded only by dead barrels is not kept alive by the forwarding link.
         */
        fun hasExternalSymbolConsumer(symbol: PsiElement): Boolean {
            val home = symbol.containingFile?.originalFile
            val homePath = home?.virtualFile?.path ?: home?.name ?: return false
            return ReferencesSearch.search(symbol, scope).findAll().any { ref ->
                val refFile = ref.element.containingFile?.originalFile ?: return@any false
                val refPath = refFile.virtualFile?.path ?: refFile.name
                refPath != homePath && classify(ref.element) is RefKind.RealConsumer
            }
        }

        private fun isLive(moduleFile: PsiFile, name: String, visited: MutableSet<Pair<String, String>>): Result {
            val origin = moduleFile.originalFile
            // NOTE: fall back to origin.name only when there is no backing file (in-memory
            // PSI in tests). Two distinct same-named in-memory files could collide here, but
            // real project files always carry a virtualFile path, so this is test-only.
            val pathKey = origin.virtualFile?.path ?: origin.name
            val key = pathKey to name
            memo[key]?.let { return Result(it, false) }
            // A Next.js entry point is consumed by the framework (no explicit importer exists), so it —
            // and any name it re-exports — is always live. This also covers barrels reached only via a page,
            // because forwardsName-gated recursion lands here on the entry-point file. Checked after the
            // memo lookup so the verdict is cached and the VFS walk isn't repeated for revisited nodes.
            if (NextEntryPoints.isEntryPoint(origin)) {
                memo[key] = true
                return Result(live = true, hitCycle = false)
            }
            // Cycle: revisiting an in-progress ancestor. Return a provisional `false` and
            // signal hitCycle so the *caller* won't cache an unproven negative as final.
            if (!visited.add(key)) return Result(live = false, hitCycle = true)

            var live = false
            var hitCycle = false
            val refs = refsCache.getOrPut(pathKey) {
                ReferencesSearch.search(origin, scope).findAll().toList()
            }
            for (ref in refs) {
                when (val kind = classify(ref.element)) {
                    RefKind.RealConsumer -> {
                        val consumed = consumedNames(ref.element)
                        if (STAR in consumed || name in consumed) { live = true; break }
                    }
                    is RefKind.ReExportSite -> {
                        val g = kind.decl.containingFile?.originalFile ?: continue
                        if (forwardsName(kind.decl, name)) {
                            for (forwarded in forwardedAs(kind.decl, name)) {
                                val child = isLive(g, forwarded, visited)
                                if (child.hitCycle) hitCycle = true
                                if (child.live) { live = true; break }
                            }
                        }
                        if (live) break
                    }
                }
            }
            // Only cache trustworthy verdicts: any positive result, or a negative that
            // completed WITHOUT hitting a cycle cutoff. A negative reached only via a cycle
            // cutoff is conditional on an ancestor still on the stack and must not poison
            // later queries that start from a different root.
            if (live || !hitCycle) memo[key] = live
            // Once this node resolved to live (or to a clean dead), it no longer depends on
            // the in-progress ancestor, so don't propagate hitCycle further up.
            return Result(live, hitCycle = hitCycle && !live)
        }

        /**
         * Is the wildcard re-export [decl] (`export * from S`) in [barrel] live? Unlike the
         * per-name [isLive], this RESOLVES the source module(s) S and asks whether any real
         * consumer of the barrel draws a name that S *actually exports* (or takes the whole
         * namespace). So a barrel that has live consumers of OTHER names still lets an
         * `export * from './SomeFun'` die when nobody imports anything SomeFun exports — while
         * `export * from './AvatarInput/AvatarInput'` stays live because a consumer imports the
         * `AvatarInput` that module exports.
         *
         * Conservative when resolution fails: if the from-clause does not resolve to any module
         * we return live (never flag what we cannot analyze).
         */
        fun isExportStarLive(barrel: PsiFile, decl: ES6ExportDeclaration): Boolean {
            val fromClause = decl.fromClause ?: return true
            val sources = ES6PsiUtil.getFromClauseResolvedReferences(fromClause)
            if (sources.isEmpty()) return true
            val sourceKey = sources.mapNotNull { it.containingFile?.originalFile?.virtualFile?.path ?: it.containingFile?.name }
                .sorted().joinToString("|")
            return reaches(barrel, decl, sources, sourceKey, HashSet())
        }

        /** Does any real consumer reachable from [file] draw a name that [sources] export (or `*`)? */
        private fun reaches(
            file: PsiFile,
            place: PsiElement,
            sources: Collection<PsiElement>,
            sourceKey: String,
            visited: MutableSet<String>,
        ): Boolean {
            val origin = file.originalFile
            val pathKey = origin.virtualFile?.path ?: origin.name
            if (!visited.add(pathKey)) return false
            // A Next.js entry point is consumed by the framework, so a wildcard reaching it is live.
            if (NextEntryPoints.isEntryPoint(origin)) return true
            val refs = refsCache.getOrPut(pathKey) {
                ReferencesSearch.search(origin, scope).findAll().toList()
            }
            for (ref in refs) {
                when (val kind = classify(ref.element)) {
                    RefKind.RealConsumer -> {
                        val consumed = consumedNames(ref.element)
                        // A namespace/require consumer takes everything the wildcard forwards.
                        if (STAR in consumed) return true
                        if (consumed.any { exportsName(sources, sourceKey, it, place) }) return true
                    }
                    is RefKind.ReExportSite -> {
                        val g = kind.decl.containingFile?.originalFile ?: continue
                        if (kind.decl.isExportAll) {
                            // `export * from barrel` forwards barrel's names (incl. S's) onward.
                            if (reaches(g, place, sources, sourceKey, visited)) return true
                        } else {
                            // `export { src as exp } from barrel`: forwards barrel's `src`. If S
                            // exports `src`, the wildcard name leaves G as `exp` — check G live for it.
                            for (spec in kind.decl.exportSpecifiers) {
                                val src = spec.referenceName ?: continue
                                if (exportsName(sources, sourceKey, src, place) && isLive(g, spec.declaredName ?: src)) {
                                    return true
                                }
                            }
                        }
                    }
                }
            }
            return false
        }

        /** Does the source module set [sources] export a name [name]? Memoized by [sourceKey]. */
        private fun exportsName(sources: Collection<PsiElement>, sourceKey: String, name: String, place: PsiElement): Boolean =
            exportsMemo.getOrPut(sourceKey to name) {
                ES6PsiUtil.resolveSymbolInModules(name, place, sources).isNotEmpty()
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
