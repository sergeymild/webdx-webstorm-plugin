package com.webdx.scsssymbols

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** Stable map key for a declaration (PsiElement isn't a safe key across passes). */
internal data class DeclKey(val file: VirtualFile, val name: String, val kind: ScssSymbols.Kind)

internal fun ScssSymbols.declKey(file: VirtualFile, name: String, kind: ScssSymbols.Kind) =
    DeclKey(file, name, kind)

/** Resolve a reference to the declaration it points at, honoring import scope + namespaces. */
internal fun ScssSymbols.resolve(ref: ScssSymbols.Ref): ScssSymbols.Decl? {
    val project = ref.file.project
    val vf = ref.file.originalFile.virtualFile ?: return null
    val candidates: List<VirtualFile> = if (ref.namespace != null) {
        (ScssImportGraph.namespaceTargets(project, vf)[ref.namespace] ?: emptySet()).toList()
    } else {
        // local file first so a local declaration wins a name collision
        val global = ScssImportGraph.globalScopeFiles(project, vf)
        listOf(vf) + (global - vf)
    }
    val psiManager = PsiManager.getInstance(project)
    for (cand in candidates) {
        val psi = psiManager.findFile(cand) ?: continue
        declarationsIn(psi).firstOrNull { it.name == ref.name && it.kind == ref.kind }?.let { return it }
    }
    return null
}

/** Project-wide map: declaration -> the references that resolve to it. Cached on PSI mod count. */
internal fun ScssSymbols.usesByDeclaration(project: Project): Map<DeclKey, List<ScssSymbols.Ref>> =
    CachedValuesManager.getManager(project).getCachedValue(project) {
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val out = HashMap<DeclKey, MutableList<ScssSymbols.Ref>>()
        for (ext in listOf("scss", "sass")) {
            for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                val psi = psiManager.findFile(vf) ?: continue
                for (ref in referencesIn(psi)) {
                    val decl = resolve(ref) ?: continue
                    val declVf = decl.file.virtualFile ?: continue
                    out.getOrPut(DeclKey(declVf, decl.name, decl.kind)) { mutableListOf() }.add(ref)
                }
            }
        }
        CachedValueProvider.Result.create<Map<DeclKey, List<ScssSymbols.Ref>>>(
            out, PsiModificationTracker.MODIFICATION_COUNT,
        )
    }
