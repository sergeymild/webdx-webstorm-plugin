package com.webdx.deadexports

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Greys an `export … from` re-export whose exported name is never reached by any real
 * (non-re-export) consumer — a "dead barrel" link — even through chains of re-exports.
 * See com.webdx.deadexports.DeadReExports for the reachability analysis.
 */
class DeadReExportInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val moduleFile = holder.file.originalFile
        val analyzer = DeadReExports.Analyzer(moduleFile.project)
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is ES6ExportDeclaration || !element.isReExport) return
                if (element.isExportAll) {
                    // `export * from` re-exports the whole namespace; flag the statement only
                    // when nothing reaches through it to a real consumer.
                    if (!analyzer.isLive(moduleFile, DeadReExports.STAR)) {
                        holder.registerProblem(element, "Re-export is never used (no consumer reaches it)",
                            ProblemHighlightType.LIKE_UNUSED_SYMBOL)
                    }
                    return
                }
                for (spec in element.exportSpecifiers) {
                    // referenceName is the SOURCE name (the name in the source module);
                    // declaredName is the publicly exported name (the alias when present).
                    val sourceName = spec.referenceName ?: continue
                    if (!analyzer.isLive(moduleFile, sourceName)) {
                        val displayName = spec.declaredName ?: sourceName
                        holder.registerProblem(spec as PsiElement, "Re-export '$displayName' is never used (no consumer reaches it)",
                            ProblemHighlightType.LIKE_UNUSED_SYMBOL)
                    }
                }
            }
        }
    }
}
