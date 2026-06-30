package com.webdx.deadexports

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ExportSpecifier
import com.intellij.lang.ecmascript6.resolve.ES6ImportHandler
import com.intellij.lang.javascript.psi.JSPsiNamedElementBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil

/**
 * Reports a directly-declared export — `export const/function/class`, `export default`, a local
 * `export { x }`, or `export interface/type/enum` — that no real (non-re-export) consumer reaches
 * through the import / re-export graph. Two distinct verdicts, via [DeadReExports.Analyzer]:
 *
 * - **Reached by an external consumer** ([DeadReExports.Analyzer.isExternallyLive]) → nothing.
 * - **Used only inside this file** by another *live* export (so the symbol is alive but its `export`
 *   keyword is redundant, e.g. `PicksItem.profile: PicksProfile`) → a **warning** suggesting it be
 *   made local.
 * - **Not reached at all** (incl. a self-reference like `SomeFun.displayName = 'SomeFun'`, or a
 *   reference from a dead sibling) → greyed as an unused symbol.
 *
 * The `… from` re-export links themselves are owned by [DeadReExportInspection].
 */
class DeadExportInspection : LocalInspectionTool() {

    override fun getStaticDescription(): String =
        "An exported declaration that no consumer reaches through the project's import / re-export graph."

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val moduleFile = holder.file.originalFile
        if (NextEntryPoints.isEntryPoint(moduleFile)) return PsiElementVisitor.EMPTY_VISITOR
        val analyzer = DeadReExports.Analyzer(moduleFile.project)

        // [symbol], when present, is the declaration the export binds. We consult it as an
        // alias-safe external backstop: a symbol-level reference search catches consumers importing
        // through a tsconfig `paths` alias (e.g. `@mu-native/ds`), which the file-name-keyed walk
        // misses. See DeadReExports.Analyzer.hasExternalSymbolConsumer.
        fun flag(name: String, anchor: PsiElement, symbol: PsiElement?) {
            // Reached by a real external consumer -> the export is needed, nothing to report.
            if (analyzer.isExternallyLive(moduleFile, name)) return
            if (symbol != null && analyzer.hasExternalSymbolConsumer(symbol)) return
            if (analyzer.isLive(moduleFile, name)) {
                // A re-export elsewhere (`export { name } from './thisFile'`) needs this `export`
                // keyword to compile, so it is NOT redundant — even if that re-export has no consumer
                // (the dead link is owned by DeadReExportInspection). Suggesting "make local" here
                // would break the re-export. So stay silent while any re-export forwards the name.
                if (analyzer.isForwardedByAnyReExport(moduleFile, name)) return
                // Used, but only inside this file (by a live same-file export). The symbol is alive;
                // only its `export` keyword is redundant -> a warning, not a dead-code grey-out.
                holder.registerProblem(anchor,
                    "Export '$name' is only used in this file; 'export' is redundant (can be made local)",
                    ProblemHighlightType.WARNING)
            } else {
                // Not reached by anyone, anywhere -> genuinely dead, grey it out.
                holder.registerProblem(anchor, "Export '$name' is never used (no consumer reaches it)",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL)
            }
        }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is ES6ExportDefaultAssignment -> {
                        val named = element.namedElement
                        val anchor = (named as? PsiNameIdentifierOwner)?.nameIdentifier ?: named ?: element
                        flag("default", anchor, named)
                    }
                    // Local `export { x as y }` only. Re-exports (`… from`) belong to DeadReExportInspection.
                    is ES6ExportSpecifier -> {
                        val decl = PsiTreeUtil.getParentOfType(element, ES6ExportDeclaration::class.java, false)
                        if (decl == null || decl.fromClause != null) return
                        val name = element.declaredName ?: return
                        val anchor = element.alias?.nameIdentifier ?: element.referenceNameElement ?: element
                        // The exported name forwards a local binding; resolve to it so the
                        // symbol-level backstop searches references to the declaration, not the
                        // `export { … }` specifier.
                        flag(name, anchor, element.reference?.resolve())
                    }
                    // Inline `export const/function/class/interface/type/enum`.
                    is JSPsiNamedElementBase -> {
                        if (!ES6ImportHandler.isExportedDirectly(element)) return
                        val name = element.name ?: return
                        val anchor = (element as? PsiNameIdentifierOwner)?.nameIdentifier ?: element
                        flag(name, anchor, element)
                    }
                }
            }
        }
    }
}
