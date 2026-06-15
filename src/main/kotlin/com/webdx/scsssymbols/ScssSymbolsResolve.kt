package com.webdx.scsssymbols

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** Stable map key for a declaration (PsiElement isn't a safe key across passes). */
internal data class DeclKey(val file: VirtualFile, val name: String, val kind: ScssSymbols.Kind)

/** A resolved reference site, by location only (no PsiElement held in the cache). */
internal data class RefLoc(val file: VirtualFile, val offset: Int)

internal fun ScssSymbols.declKey(file: VirtualFile, name: String, kind: ScssSymbols.Kind) =
    DeclKey(file, name, kind)

/**
 * Project-wide declaration index by TEXT (no PSI/AST loaded): file -> its declaration hits.
 * Cached on PSI mod count, mirroring `ScssImportGraph.graph()` / `CssModules.scssSymbolIndex`.
 */
private fun declTextIndex(project: Project): Map<VirtualFile, List<ScssSymbols.DeclHit>> =
    CachedValuesManager.getManager(project).getCachedValue(project) {
        val scope = GlobalSearchScope.projectScope(project)
        val out = HashMap<VirtualFile, List<ScssSymbols.DeclHit>>()
        for (ext in listOf("scss", "sass")) {
            for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: continue
                out[vf] = ScssSymbols.declarationsText(text)
            }
        }
        CachedValueProvider.Result.create<Map<VirtualFile, List<ScssSymbols.DeclHit>>>(
            out, PsiModificationTracker.MODIFICATION_COUNT,
        )
    }

/**
 * Resolve a reference (by name/kind/namespace, in [refVf]) to its declaring file + name offset,
 * honoring import scope + namespaces — TEXT only, so it never loads project PSI.
 */
private fun resolveLoc(
    project: Project,
    refVf: VirtualFile,
    name: String,
    kind: ScssSymbols.Kind,
    namespace: String?,
): Pair<VirtualFile, Int>? {
    val candidates: List<VirtualFile> = if (namespace != null) {
        (ScssImportGraph.namespaceTargets(project, refVf)[namespace] ?: emptySet()).toList()
    } else {
        // local file first so a local declaration wins a name collision
        val global = ScssImportGraph.globalScopeFiles(project, refVf)
        listOf(refVf) + (global - refVf)
    }
    val index = declTextIndex(project)
    for (cand in candidates) {
        index[cand]?.firstOrNull { it.name == name && it.kind == kind }?.let { return cand to it.offset }
    }
    return null
}

/** Resolve a reference to the declaration it points at (materializes the target PSI element). */
internal fun ScssSymbols.resolve(ref: ScssSymbols.Ref): ScssSymbols.Decl? {
    val project = ref.file.project
    val refVf = ref.file.originalFile.virtualFile ?: return null
    val (declVf, offset) = resolveLoc(project, refVf, ref.name, ref.kind, ref.namespace) ?: return null
    val psi = PsiManager.getInstance(project).findFile(declVf) ?: return null
    val element = psi.findElementAt(offset) ?: return null
    return ScssSymbols.Decl(ref.name, ref.kind, element, psi)
}

/**
 * Project-wide map: declaration -> the reference LOCATIONS that resolve to it. Built from raw
 * file text only (no PSI/AST), so it scales to large projects. Cached on PSI mod count.
 * Find Usages materializes PsiElements from the locations on demand; the unused inspection
 * only needs key presence.
 */
internal fun ScssSymbols.usesByDeclaration(project: Project): Map<DeclKey, List<RefLoc>> =
    CachedValuesManager.getManager(project).getCachedValue(project) {
        val scope = GlobalSearchScope.projectScope(project)
        val out = HashMap<DeclKey, MutableList<RefLoc>>()
        for (ext in listOf("scss", "sass")) {
            for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: continue
                for (hit in referencesText(text)) {
                    val (declVf, _) = resolveLoc(project, vf, hit.name, hit.kind, hit.namespace) ?: continue
                    out.getOrPut(DeclKey(declVf, hit.name, hit.kind)) { mutableListOf() }.add(RefLoc(vf, hit.offset))
                }
            }
        }
        CachedValueProvider.Result.create<Map<DeclKey, List<RefLoc>>>(
            out, PsiModificationTracker.MODIFICATION_COUNT,
        )
    }
