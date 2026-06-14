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
 * Greys a directly-declared export — `export const/function/class`, `export default`, a local
 * `export { x }`, or `export interface/type/enum` — whose exported name is never reached by any
 * real (non-re-export) consumer through the import / re-export graph. Liveness is delegated to
 * [DeadReExports.Analyzer.isLive], which searches references to the *module file*, so same-file
 * uses (e.g. `SomeFun.displayName = 'SomeFun'`) do not keep an export alive. The `… from`
 * re-export links themselves are owned by [DeadReExportInspection].
 */
class DeadExportInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val moduleFile = holder.file.originalFile
        if (NextEntryPoints.isEntryPoint(moduleFile)) return PsiElementVisitor.EMPTY_VISITOR
        val analyzer = DeadReExports.Analyzer(moduleFile.project)

        fun flag(name: String, anchor: PsiElement) {
            if (!analyzer.isLive(moduleFile, name)) {
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
                        flag("default", anchor)
                    }
                    // Local `export { x as y }` only. Re-exports (`… from`) belong to DeadReExportInspection.
                    is ES6ExportSpecifier -> {
                        val decl = PsiTreeUtil.getParentOfType(element, ES6ExportDeclaration::class.java, false)
                        if (decl == null || decl.fromClause != null) return
                        val name = element.declaredName ?: return
                        val anchor = element.alias?.nameIdentifier ?: element.referenceNameElement ?: element
                        flag(name, anchor)
                    }
                    // Inline `export const/function/class/interface/type/enum`.
                    is JSPsiNamedElementBase -> {
                        if (!ES6ImportHandler.isExportedDirectly(element)) return
                        val name = element.name ?: return
                        val anchor = (element as? PsiNameIdentifierOwner)?.nameIdentifier ?: element
                        flag(name, anchor)
                    }
                }
            }
        }
    }
}
