package com.intch.cssmodules

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.css.CssClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

/**
 * Find Usages / Cmd+Click on a class selector inside a CSS-module file
 * (`*.module.css|scss|less|sass`) lists ONLY the usages inside the JS/TS files
 * that import that exact module.
 *
 * IMPORTANT: it does NOT use the platform's reference search for the CSS class,
 * because that resolves every candidate `styles.foo` through the TypeScript
 * language service — which, with the tsgo service, blocks under a read lock and
 * freezes the IDE. Instead we:
 *   1. find the importer files via a cheap file-reference search on the module,
 *   2. scan those files' PSI for `<binding>.<className>` property accesses,
 * with zero type resolution. Fast and freeze-free.
 */
class CssModuleFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    private val log = logger<CssModuleFindUsagesHandlerFactory>()

    override fun canFindUsages(element: PsiElement): Boolean {
        runCatching {
            java.io.File("/tmp/css-scoped-canfind.txt")
                .writeText("canFindUsages called: element=${element.javaClass.name} file=${element.containingFile?.name}")
        }
        val file = element.containingFile ?: return false
        val isModule = isCssModuleFile(file)
        val cssClass = resolveCssClass(element)
        if (isModule) {
            log.warn(
                "[CSS-SCOPED] canFindUsages: element=${element.javaClass.name} " +
                    "text='${runCatching { element.text?.take(30) }.getOrNull()}' " +
                    "file=${file.name} cssClass=${cssClass?.javaClass?.name} " +
                    "parents=[${parentChain(element)}]"
            )
        }
        return isModule && cssClass != null
    }

    private fun parentChain(element: PsiElement): String {
        val sb = StringBuilder()
        var cur: PsiElement? = element.parent
        var depth = 0
        while (cur != null && depth < 5) {
            if (depth > 0) sb.append(" > ")
            sb.append(cur.javaClass.simpleName)
            cur = cur.parent
            depth++
        }
        return sb.toString()
    }

    override fun createFindUsagesHandler(
        element: PsiElement,
        forHighlightUsages: Boolean,
    ): FindUsagesHandler {
        return object : FindUsagesHandler(element) {
            override fun getFindUsagesOptions(dataContext: com.intellij.openapi.actionSystem.DataContext?): FindUsagesOptions {
                // Default options are fine; we do the scoping ourselves below.
                return super.getFindUsagesOptions(dataContext)
            }

            override fun processElementUsages(
                target: PsiElement,
                processor: Processor<in UsageInfo>,
                options: FindUsagesOptions,
            ): Boolean {
                return processScoped(target, processor)
            }
        }
    }

    private fun processScoped(target: PsiElement, processor: Processor<in UsageInfo>): Boolean {
        // All PSI access below needs a read action; processElementUsages runs on a
        // pooled thread without one.
        return ReadAction.compute<Boolean, RuntimeException> {
            val cssClass = resolveCssClass(target) ?: return@compute true
            val className = cssClass.name?.removePrefix(".")?.takeIf { it.isNotEmpty() } ?: return@compute true
            val moduleFile = target.containingFile ?: return@compute true
            val project = moduleFile.project

            // file -> the local binding names this file uses for the module (e.g. "styles")
            val importers = LinkedHashMap<PsiFile, MutableSet<String>>()
            ReferencesSearch
                .search(moduleFile, GlobalSearchScope.projectScope(project))
                .forEach { ref ->
                    val f = ref.element.containingFile ?: return@forEach
                    val set = importers.getOrPut(f) { linkedSetOf() }
                    importBindingName(ref.element)?.let { set.add(it) }
                }

            log.warn("[CSS-SCOPED] class='$className' module='${moduleFile.name}' importers=${importers.keys.map { it.name }}")

            var count = 0
            for ((file, bindings) in importers) {
                val leaves = PsiTreeUtil.collectElements(file) { el ->
                    el.firstChild == null && el.textLength == className.length && el.text == className
                }
                for (leaf in leaves) {
                    val dot = prevMeaningfulLeaf(leaf) ?: continue
                    if (dot.text != ".") continue // must be a `.className` property access
                    if (bindings.isNotEmpty()) {
                        val qualifier = prevMeaningfulLeaf(dot) ?: continue
                        if (qualifier.text !in bindings) continue // qualified by the module import
                    }
                    if (!processor.process(UsageInfo(leaf))) return@compute false
                    count++
                }
            }
            log.warn("[CSS-SCOPED] reported $count usage(s) for '$className'")
            true
        }
    }

    /** Pull the default import binding name out of the import statement, e.g. `styles`. */
    private fun importBindingName(refElement: PsiElement): String? {
        var cur: PsiElement? = refElement
        var depth = 0
        while (cur != null && depth < 12) {
            val text = cur.text
            if (text.startsWith("import")) {
                Regex("""import\s+(\w+)""").find(text)?.let { return it.groupValues[1] }
                return null
            }
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun prevMeaningfulLeaf(el: PsiElement): PsiElement? {
        var p = PsiTreeUtil.prevLeaf(el)
        while (p != null && (p is PsiWhiteSpace || p is PsiComment)) {
            p = PsiTreeUtil.prevLeaf(p)
        }
        return p
    }

    private fun isCssModuleFile(file: PsiFile): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".module.css") ||
            name.endsWith(".module.scss") ||
            name.endsWith(".module.sass") ||
            name.endsWith(".module.less")
    }

    private fun resolveCssClass(element: PsiElement): CssClass? {
        return element as? CssClass
            ?: PsiTreeUtil.getParentOfType(element, CssClass::class.java, false)
    }
}
