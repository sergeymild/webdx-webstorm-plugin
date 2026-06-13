package com.webdx.rnstyles

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import com.webdx.cssmodules.CssModules

/**
 * Find Usages on a `StyleSheet.create` key property reports ONLY the `<binding>.<key>`
 * accesses within the key's scope — the containing file for an inline object, the importer
 * files for an exported one. Scans plain PSI leaves, with no type resolution, so it does not
 * match same-named members of unrelated objects.
 */
class RnStyleFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean = targetProperty(element) != null

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler {
        // The platform only calls this after canFindUsages returned true, so targetProperty is non-null.
        val prop = requireNotNull(targetProperty(element)) { "createFindUsagesHandler called without a style-key target" }
        return object : FindUsagesHandler(prop) {
            override fun processElementUsages(
                target: PsiElement,
                processor: Processor<in UsageInfo>,
                options: FindUsagesOptions,
            ): Boolean = ReadAction.compute<Boolean, RuntimeException> { processScoped(prop, processor) }
        }
    }

    /** The style-key JSProperty for [element]: a key property directly, or a usage resolving to one. */
    private fun targetProperty(element: PsiElement): JSProperty? {
        styleKeyProperty(element)?.let { return it }
        return RnStyles.resolveKeyProperty(element)
    }

    /** [element] is (or is inside) a JSProperty that is a top-level key of a StyleSheet object. */
    private fun styleKeyProperty(element: PsiElement): JSProperty? {
        val prop = element as? JSProperty
            ?: PsiTreeUtil.getParentOfType(element, JSProperty::class.java, false)
            ?: return null
        val obj = prop.parent as? JSObjectLiteralExpression ?: return null
        val argList = obj.parent
        if (argList !is JSArgumentList) return null
        val call = argList.parent as? JSCallExpression ?: return null
        return if (RnStyles.isStyleSheetCreateCall(call)) prop else null
    }

    private fun processScoped(prop: JSProperty, processor: Processor<in UsageInfo>): Boolean {
        val key = prop.name ?: return true
        val obj = prop.parent as? JSObjectLiteralExpression ?: return true
        val definingFile = obj.containingFile?.originalFile ?: return true
        val binding = RnStyles.bindingNameOf(obj) ?: return true

        val scope = LinkedHashMap<PsiFile, MutableSet<String>>()
        scope.getOrPut(definingFile) { linkedSetOf() }.add(binding)
        if (RnStyles.isExported(obj)) {
            for ((f, locals) in RnStyles.importersForExport(definingFile, binding)) {
                scope.getOrPut(f) { linkedSetOf() }.addAll(locals)
            }
        }

        for ((file, bindings) in scope) {
            val leaves = PsiTreeUtil.collectElements(file) { el ->
                el.firstChild == null && el.textLength == key.length && el.text == key
            }
            for (leaf in leaves) {
                val dot = CssModules.prevMeaningfulLeaf(leaf)
                if (dot != null && dot.text == ".") {
                    val qualifier = CssModules.prevMeaningfulLeaf(dot) ?: continue
                    if (qualifier.text !in bindings) continue
                    // Skip chained access (e.g. `theme.styles.key`): qualifier is itself a member.
                    val dotBeforeQ = CssModules.prevMeaningfulLeaf(qualifier)
                    if (dotBeforeQ != null && dotBeforeQ.text == ".") continue
                    if (!processor.process(UsageInfo(leaf))) return false
                } else {
                    // Not a member access. Could be a `const { key } = <binding>` destructuring
                    // site, or a use of that destructured local. Exclude the key's own (and any
                    // other StyleSheet's) declaration leaf — that's a definition, not a usage.
                    val asDecl = styleKeyProperty(leaf)
                    if (asDecl != null && asDecl.nameIdentifier == leaf) continue
                    if (sameProperty(RnStyles.resolveKeyProperty(leaf), prop)) {
                        if (!processor.process(UsageInfo(leaf))) return false
                    }
                }
            }
        }
        return true
    }

    /** Robust identity for a resolved key property vs the target (covers cross-file resolution). */
    private fun sameProperty(a: JSProperty?, b: JSProperty): Boolean {
        if (a == null) return false
        if (a == b) return true
        return a.containingFile?.originalFile == b.containingFile?.originalFile && a.textRange == b.textRange
    }
}
