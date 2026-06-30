package com.webdx.deadexports

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

/**
 * Find Usages / Show Usages on a re-export specifier (`export { X } from '…'`) lists ONLY the
 * direct, single-hop sites that draw `X` from THIS barrel file — the next-level re-export that
 * forwards it and any module importing it straight from here — instead of the symbol's whole
 * project-wide fan-out. It does NOT chase `X` through further barrels to the leaf components that
 * ultimately use it (see [BarrelReExportUsages]).
 *
 * Registered order="first" so it owns the export specifier before the platform's default
 * symbol search. It drives both Alt+F7 and the Cmd+Click Show Usages popup
 * ([com.webdx.cssmodules.CssModuleGotoDeclarationAction]). No TypeScript service is touched.
 */
class BarrelReExportFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean =
        ReadAction.compute<Boolean, RuntimeException> { BarrelReExportUsages.reExportSpecifierAt(element) != null }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler {
        return object : FindUsagesHandler(element) {
            override fun processElementUsages(
                target: PsiElement,
                processor: Processor<in UsageInfo>,
                options: FindUsagesOptions,
            ): Boolean = ReadAction.compute<Boolean, RuntimeException> {
                val spec = BarrelReExportUsages.reExportSpecifierAt(target) ?: return@compute true
                val name = BarrelReExportUsages.exportedName(spec) ?: return@compute true
                val barrel = spec.containingFile?.originalFile ?: return@compute true
                for (usage in BarrelReExportUsages.collect(barrel, name)) {
                    if (!processor.process(UsageInfo(usage))) return@compute false
                }
                true
            }
        }
    }
}
