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
    fun detectStyle(text: String): Style = TODO("Task 2")
    fun reExportLine(name: String, defaultAs: Boolean, specifier: String, style: Style): String = TODO("Task 2")
    fun forwardsName(text: String, name: String, specifier: String): Boolean = TODO("Task 2")
    fun forwardsDefaultFrom(text: String, specifier: String): Boolean = TODO("Task 2")
    fun exportedNameAt(element: PsiElement): Pair<String, Boolean>? = TODO("Task 5")
    fun planFor(componentFile: PsiFile, name: String, isDefault: Boolean, project: Project): Plan? = TODO("Task 6")
}
