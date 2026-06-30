package com.webdx.deadexports

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ExportSpecifier
import com.intellij.lang.ecmascript6.resolve.ES6PsiUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * "Where does this re-export go directly?" — for the name in an `export { X } from '…'`, the
 * files that reference THIS barrel file and draw `X` from it in a single hop: the next-level
 * re-export that forwards it (`export { X } from './thisBarrel'`) and any module that imports it
 * straight from this barrel (`import { X } from '@/this/barrel'`).
 *
 * Deliberately NOT transitive: it does not chase `X` through further barrels to the leaf
 * components that ultimately render it. From `UI-KIT/UniversalCard/index.ts` the answer is just
 * `UI-KIT/index.tsx` (which re-exports it onward) — not the screens that import from `@/UI-KIT`.
 * That keeps the result anchored to the file you clicked, instead of the symbol's whole fan-out.
 *
 * Reuses [DeadReExports.classify] / [DeadReExports.consumedNames] so re-export-vs-real-consumer
 * classification and `@/`-alias / `require` handling stay identical to the dead-code inspections.
 */
object BarrelReExportUsages {

    /**
     * The named re-export specifier under [element] — i.e. the `X` in `export { X } from '…'`
     * (or the alias `Y` in `export { X as Y } from '…'`) — when [element] sits inside such a
     * declaration. Null for a local `export { X }` (no `from`), for `export *` (no name to click),
     * and for anything outside a re-export.
     */
    fun reExportSpecifierAt(element: PsiElement): ES6ExportSpecifier? {
        val spec = element as? ES6ExportSpecifier
            ?: PsiTreeUtil.getParentOfType(element, ES6ExportSpecifier::class.java, false)
            ?: return null
        val decl = PsiTreeUtil.getParentOfType(spec, ES6ExportDeclaration::class.java, false)
        return if (decl != null && decl.isReExport && !decl.isExportAll) spec else null
    }

    /** The publicly exported name of [spec] (the alias when present, else the source name). */
    fun exportedName(spec: ES6ExportSpecifier): String? = spec.declaredName ?: spec.referenceName

    /**
     * The source declaration this re-export points at — "the component itself" behind
     * `export { X } from './X'`. Resolves the from-clause module(s) and the symbol [spec] forwards
     * (its source name), following the chain down to the real declaration. Null when the from-clause
     * or the symbol cannot be resolved.
     */
    fun resolveSource(spec: ES6ExportSpecifier): PsiElement? {
        val decl = PsiTreeUtil.getParentOfType(spec, ES6ExportDeclaration::class.java, false) ?: return null
        val fromClause = decl.fromClause ?: return null
        val sourceName = spec.referenceName ?: return null
        val modules = ES6PsiUtil.getFromClauseResolvedReferences(fromClause)
        if (modules.isEmpty()) return null
        return ES6PsiUtil.resolveSymbolInModules(sourceName, spec, modules)
            .firstNotNullOfOrNull { it.element }
    }

    /**
     * The direct (single-hop) reference sites that draw [exportedName] from [barrel]: each
     * `export { … } from './barrel'` that forwards the name onward, and each `import { … } from
     * './barrel'` that consumes it. The element returned is the referencing import/export site
     * itself — no recursion into onward barrels, no expansion into the consumer's local uses.
     */
    fun collect(barrel: PsiFile, exportedName: String): List<PsiElement> {
        val origin = barrel.originalFile
        val project: Project = barrel.project
        val scope = GlobalSearchScope.projectScope(project)
        val results = ArrayList<PsiElement>()
        for (ref in ReferencesSearch.search(origin, scope).findAll()) {
            val drawsName = when (val kind = DeadReExports.classify(ref.element)) {
                DeadReExports.RefKind.RealConsumer -> {
                    val consumed = DeadReExports.consumedNames(ref.element)
                    DeadReExports.STAR in consumed || exportedName in consumed
                }
                is DeadReExports.RefKind.ReExportSite -> forwardsName(kind.decl, exportedName)
            }
            if (drawsName) results.add(ref.element)
        }
        return results.distinct()
    }

    /** Does re-export [decl] forward [name] onward (a matching specifier, or `export *`)? */
    private fun forwardsName(decl: ES6ExportDeclaration, name: String): Boolean {
        if (decl.isExportAll) return true
        return decl.exportSpecifiers.any { it.referenceName == name }
    }
}
