package com.webdx.deadexports

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6NamespaceImportExport
import com.intellij.lang.ecmascript6.resolve.ES6ImportHandler
import com.intellij.lang.ecmascript6.resolve.ES6PsiUtil
import com.intellij.lang.javascript.psi.JSPsiNamedElementBase
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
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
        // Verdict memos are per-mode: the full liveness (which also counts a reference from a live
        // same-file export) and the external-only liveness give different answers for the same
        // (path, name), so they cannot share one cache.
        private val memoFull = HashMap<Pair<String, String>, Boolean>()
        private val memoExternal = HashMap<Pair<String, String>, Boolean>()
        // Per-origin-file reference list cache: ReferencesSearch is a project-wide scan, so a
        // barrel with N specifiers would otherwise re-run the SAME search N times. Keyed on the
        // same path string used for the memo key. The verdict memo dedupes by (path, name); this
        // dedupes the underlying search by path.
        private val refsCache = HashMap<String, List<com.intellij.psi.PsiReference>>()
        // Memo for "does source module set <sourceKey> export <name>?" — resolveSymbolInModules is
        // a per-name module resolution, and the same names recur across a barrel's many consumers.
        private val exportsMemo = HashMap<Pair<String, String>, Boolean>()
        // Per-(file, name) cache of in-file references to the symbol exported as that name — used by
        // the same-file liveness pass. A find-usages search per export, deduped by the memo key.
        private val sameFileRefsCache = HashMap<Pair<String, String>, List<PsiReference>>()

        /**
         * Result of one recursive [isLive] computation: the liveness verdict plus whether
         * that verdict was (transitively) influenced by a cycle cutoff against an
         * in-progress ancestor. A `false` verdict that [hitCycle] only because the
         * visited-guard short-circuited an ancestor is *provisional* — it holds for this
         * traversal but is NOT a final answer, so it must not be memoized for later
         * top-level queries. A `true` verdict is always final regardless of [hitCycle].
         */
        private data class Result(val live: Boolean, val hitCycle: Boolean)

        /**
         * Is [name] (a name exported by [moduleFile]) used *at all* — reached by a real external
         * consumer (directly or through the re-export graph), or transitively via another *live*
         * same-file export that references it? When this is true but [isExternallyLive] is false,
         * the symbol is alive yet its `export` keyword is redundant (it could be made local).
         */
        fun isLive(moduleFile: PsiFile, name: String): Boolean =
            isLive(moduleFile, name, sameFile = true, HashSet()).live

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

        /**
         * Is [name] reached by a real *external* consumer (import / require / dynamic import),
         * directly or through the re-export graph? Ignores same-file uses — so this answers "is the
         * `export` keyword needed", as opposed to [isLive]'s "is the symbol used at all".
         */
        fun isExternallyLive(moduleFile: PsiFile, name: String): Boolean =
            isLive(moduleFile, name, sameFile = false, HashSet()).live

        /**
         * Does any *other* module forward [name] out of [moduleFile] with an `export … from` (a named
         * specifier or `export *`)? When true, the `export` keyword on [moduleFile]'s declaration is
         * syntactically REQUIRED for that re-export to compile — so it is never "redundant", even
         * when the re-export itself has no consumer (the dead link is owned by [DeadReExportInspection]).
         * A single hop suffices: the immediate `export { name } from './thisFile'` is what depends on
         * the keyword. Distinct from [isExternallyLive], which discounts a re-export nobody consumes.
         */
        fun isForwardedByAnyReExport(moduleFile: PsiFile, name: String): Boolean {
            val origin = moduleFile.originalFile
            val pathKey = origin.virtualFile?.path ?: origin.name
            val refs = refsCache.getOrPut(pathKey) {
                ReferencesSearch.search(origin, scope).findAll().toList()
            }
            return refs.any { ref ->
                val kind = classify(ref.element)
                kind is RefKind.ReExportSite && forwardsName(kind.decl, name)
            }
        }

        private fun isLive(
            moduleFile: PsiFile,
            name: String,
            sameFile: Boolean,
            visited: MutableSet<Pair<String, String>>,
        ): Result {
            val origin = moduleFile.originalFile
            // NOTE: fall back to origin.name only when there is no backing file (in-memory
            // PSI in tests). Two distinct same-named in-memory files could collide here, but
            // real project files always carry a virtualFile path, so this is test-only.
            val pathKey = origin.virtualFile?.path ?: origin.name
            val key = pathKey to name
            val memo = if (sameFile) memoFull else memoExternal
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
                                val child = isLive(g, forwarded, sameFile, visited)
                                if (child.hitCycle) hitCycle = true
                                if (child.live) { live = true; break }
                            }
                        }
                        if (live) break
                    }
                }
            }
            // Same-file liveness (full mode only): an export N is also live when another export M in
            // the SAME file references N's symbol and M is itself live — so a type/value that is only
            // part of another *used* export's surface (`PicksItem.profile: PicksProfile`) stays alive.
            // A self-reference (`SomeFun.displayName = 'SomeFun'`) or a reference from a *dead* sibling
            // does not. Skipped in external-only mode, which answers "is the `export` keyword needed".
            if (sameFile && !live) {
                for (ref in sameFileRefs(origin, pathKey, name)) {
                    val carrier = enclosingExportedName(ref.element) ?: continue
                    if (carrier == name) continue // a symbol referencing itself never keeps itself alive
                    val child = isLive(origin, carrier, sameFile, visited)
                    if (child.hitCycle) hitCycle = true
                    if (child.live) { live = true; break }
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

        /**
         * In-file references to the symbol(s) [file] *directly declares* and exports as [name]. We
         * locate the declared symbol by scanning [file] (not via cross-module resolution — that
         * follows `… from` re-export chains and the IDE's resolver blows the stack on cyclic
         * barrels), then run a find-usages search restricted to [file]'s own scope. External uses
         * are already covered by the module-file reference scan in [isLive], so we only need
         * same-file uses here, and a pure re-export name (no local declaration) yields none.
         * Memoized by [pathKey] to [name].
         */
        private fun sameFileRefs(file: PsiFile, pathKey: String, name: String): List<PsiReference> =
            sameFileRefsCache.getOrPut(pathKey to name) {
                val symbols = PsiTreeUtil.findChildrenOfType(file, JSPsiNamedElementBase::class.java)
                    .filter { it.name == name && ES6ImportHandler.isExportedDirectly(it) }
                if (symbols.isEmpty()) emptyList()
                else {
                    val fileScope = GlobalSearchScope.fileScope(file)
                    symbols.flatMap { ReferencesSearch.search(it, fileScope).findAll() }
                }
            }

        /**
         * The name of the nearest directly-exported declaration enclosing [refElement], or null when
         * the reference is not inside any exported declaration (e.g. a top-level
         * `SomeFun.displayName = …` statement). This is the "carrier" export whose liveness decides
         * whether the referenced symbol is kept alive.
         */
        private fun enclosingExportedName(refElement: PsiElement): String? {
            var el: PsiElement? = refElement
            while (el != null && el !is PsiFile) {
                if (el is JSPsiNamedElementBase && ES6ImportHandler.isExportedDirectly(el)) {
                    el.name?.let { return it }
                }
                el = el.parent
            }
            return null
        }
    }
}
