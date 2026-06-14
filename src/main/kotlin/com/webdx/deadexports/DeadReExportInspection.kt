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
        if (NextEntryPoints.isEntryPoint(moduleFile)) return PsiElementVisitor.EMPTY_VISITOR
        val analyzer = DeadReExports.Analyzer(moduleFile.project)
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is ES6ExportDeclaration || !element.isReExport) return
                if (element.isExportAll) {
                    // `export * from S` re-exports S's whole namespace; flag the statement only
                    // when no real consumer draws a name that S actually exports. Resolves S so a
                    // live barrel can still have a dead `export *` among its links.
                    if (!analyzer.isExportStarLive(moduleFile, element)) {
                        holder.registerProblem(element, "Re-export is never used (no consumer reaches it)",
                            ProblemHighlightType.LIKE_UNUSED_SYMBOL)
                    }
                    return
                }
                for (spec in element.exportSpecifiers) {
                    // referenceName is the SOURCE name (the name in the source module);
                    // declaredName is the publicly exported name (the alias when present).
                    val sourceName = spec.referenceName ?: continue
                    // Query liveness by the EXPORTED name, not the source name. Analyzer.isLive's
                    // contract is `name` = a name exported by `moduleFile`: consumers import the
                    // exported name and the recursion propagates it. For `Inner as Outer` the two
                    // diverge, so using the source name false-flagged a consumed aliased re-export.
                    // For a non-aliased specifier displayName == sourceName, so behavior is unchanged.
                    val displayName = spec.declaredName ?: sourceName
                    if (!analyzer.isLive(moduleFile, displayName)) {
                        // Anchor on the exported-name identifier rather than the whole specifier:
                        // for `X as Y` the alias's nameIdentifier is `Y`; with no alias the
                        // referenceNameElement is the single name that is both source and export.
                        // Verified against the SDK (javascript-plugin.jar): ES6ExportSpecifier is a
                        // JSPsiNamedElementBase (PsiNamedElement, NOT PsiNameIdentifierOwner) but a
                        // JSPsiReferenceElement, so getReferenceNameElement() yields the name PSI;
                        // getAlias() is an ES6ExportSpecifierAlias (a JSNamedElement →
                        // PsiNameIdentifierOwner) whose nameIdentifier is the exported alias.
                        // Fall back to the specifier itself if neither is available. The
                        // `spec as PsiElement` cast selects the PsiElement overload of
                        // registerProblem (ES6ExportSpecifier is also a PsiReference).
                        val anchor: PsiElement =
                            spec.alias?.nameIdentifier ?: spec.referenceNameElement ?: (spec as PsiElement)
                        holder.registerProblem(anchor, "Re-export '$displayName' is never used (no consumer reaches it)",
                            ProblemHighlightType.LIKE_UNUSED_SYMBOL)
                    }
                }
            }
        }
    }
}
