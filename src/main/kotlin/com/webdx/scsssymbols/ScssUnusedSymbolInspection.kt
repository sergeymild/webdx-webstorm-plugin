package com.webdx.scsssymbols

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Greys a SCSS symbol declaration (`$var` / `@function` / `@mixin` / `%placeholder`) that
 * no reference in the project resolves to, through the `@use`/`@import`/`@forward` graph.
 * Registered once on `language="CSS"` (covers SCSS/SASS dialects without duplication —
 * per-dialect registration fires the inspection 2-3× on a single .scss file).
 *
 * Uses the identity-visitor form: pre-compute a map of unused declaration elements, then
 * register problems from `visitElement` when the visited element is one of those keys.
 * This mirrors [com.webdx.cssmodules.CssModuleUnusedClassInspection] and is required because
 * `registerProblem` inside `buildVisitor` (before returning the visitor) is not picked up
 * by the platform's highlighting pass.
 */
class ScssUnusedSymbolInspection : LocalInspectionTool() {

    override fun getStaticDescription(): String =
        "An SCSS variable, function, mixin, or placeholder that is never used through the @use / @import / @forward graph."

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        val name = file.name.lowercase()
        if (!name.endsWith(".scss") && !name.endsWith(".sass")) return PsiElementVisitor.EMPTY_VISITOR

        val vf = file.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val uses = ScssSymbols.usesByDeclaration(file.project)
        val decls = ScssSymbols.declarationsIn(file)

        // Build an identity map: element -> Decl, for each UNUSED declaration.
        val unusedElements = HashMap<PsiElement, ScssSymbols.Decl>()
        for (decl in decls) {
            val key = DeclKey(vf, decl.name, decl.kind)
            if (uses[key].isNullOrEmpty()) {
                unusedElements[decl.element] = decl
            }
        }

        if (unusedElements.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                unusedElements[element]?.let { decl ->
                    holder.registerProblem(
                        element,
                        "Unused SCSS ${decl.kind.name.lowercase()} '${decl.name}'",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    )
                }
            }
        }
    }
}
