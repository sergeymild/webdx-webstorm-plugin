package com.webdx.i18n

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

/**
 * Find Usages / Cmd+Click on a translation-key property inside a locale JSON lists
 * ONLY the `t('key')` / `<Trans i18nKey>` references that resolve to that exact key
 * — instead of every property with the same name across every locale file.
 *
 * We search references to the canonical key-source property (en.json), which our
 * [I18nKeyReference] resolves to. No TypeScript service is touched.
 */
class I18nKeyFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean =
        ReadAction.compute<Boolean, RuntimeException> { localeKeyProperty(element) != null }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler {
        return object : FindUsagesHandler(element) {
            override fun processElementUsages(
                target: PsiElement,
                processor: Processor<in UsageInfo>,
                options: FindUsagesOptions,
            ): Boolean = ReadAction.compute<Boolean, RuntimeException> {
                val prop = localeKeyProperty(target) ?: return@compute true
                val project = prop.project
                val key = I18nKeys.pathOf(prop)
                // The canonical property our references resolve to (en.json), even when
                // Find Usages was invoked from a sibling locale file.
                val canonical = I18nKeyIndex.resolve(project, key) ?: return@compute true
                val scope = options.searchScope ?: GlobalSearchScope.projectScope(project)
                // Keep only our t()/<Trans> references — the JSON plugin also links the
                // same key across sibling locale files, which we don't want to list.
                for (ref in ReferencesSearch.search(canonical, scope)) {
                    if (ref !is I18nKeyReference) continue
                    if (!processor.process(UsageInfo(ref.element))) return@compute false
                }
                true
            }
        }
    }

    private fun localeKeyProperty(element: PsiElement): JsonProperty? {
        val prop = element as? JsonProperty
            ?: PsiTreeUtil.getParentOfType(element, JsonProperty::class.java, false)
            ?: return null
        val project = element.project
        val keySource = I18nKeyIndex.keySourceFile(project)?.virtualFile ?: return null
        val containing = prop.containingFile?.virtualFile ?: return null
        // Only locale files sitting next to the key source (en.json), and only real keys.
        if (containing.parent != keySource.parent) return null
        val key = I18nKeys.pathOf(prop)
        return if (key in I18nKeyIndex.keys(project)) prop else null
    }
}
