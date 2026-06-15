package com.webdx.scsssymbols

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

/**
 * Find Usages on a SCSS symbol declaration → every reference the import graph resolves to
 * it, project-wide. Reuses the cached `usesByDeclaration` map; no platform reference search
 * (avoids resolving each candidate through the TS service).
 */
class ScssSymbolFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        val name = file.name.lowercase()
        if (!name.endsWith(".scss") && !name.endsWith(".sass")) return false
        return declAt(element) != null
    }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler {
        return object : FindUsagesHandler(element) {
            override fun getFindUsagesOptions(dataContext: DataContext?): FindUsagesOptions =
                super.getFindUsagesOptions(dataContext)

            override fun processElementUsages(
                target: PsiElement,
                processor: Processor<in UsageInfo>,
                options: FindUsagesOptions,
            ): Boolean = ReadAction.compute<Boolean, RuntimeException> {
                val decl = declAt(target) ?: return@compute true
                val vf = decl.file.virtualFile ?: return@compute true
                val key = DeclKey(vf, decl.name, decl.kind)
                val locs = ScssSymbols.usesByDeclaration(target.project)[key] ?: emptyList()
                val psiManager = com.intellij.psi.PsiManager.getInstance(target.project)
                for (loc in locs) {
                    // materialize the PsiElement only for this declaration's actual usages
                    val element = psiManager.findFile(loc.file)?.findElementAt(loc.offset) ?: continue
                    if (!processor.process(UsageInfo(element))) return@compute false
                }
                true
            }
        }
    }

    /** The declaration whose name-token contains [element]'s offset, or null. */
    private fun declAt(element: PsiElement): ScssSymbols.Decl? {
        val file = element.containingFile?.originalFile ?: return null
        val offset = element.textOffset
        return ScssSymbols.declarationsIn(file).firstOrNull { it.element.textRange.contains(offset) }
    }
}
