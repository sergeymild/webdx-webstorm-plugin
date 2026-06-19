package com.webdx.cssmodules

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/**
 * Legacy Go to Declaration path for `styles.<class>`, delegating to the shared
 * [CssModuleClassNavigation] resolver (single effective declaration; local override
 * wins). The modern Ctrl+Click path is handled by [CssModuleDirectNavigationProvider];
 * this covers the legacy `GotoDeclarationAction` path.
 */
class CssModuleGotoDeclarationHandler : GotoDeclarationHandler {

    private val log = logger<CssModuleGotoDeclarationHandler>()

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val target = CssModuleClassNavigation.resolveTarget(element)
            ?: CssModuleClassNavigation.resolveExtendTarget(element)
        // DIAGNOSTIC (temporary): grep idea.log for "[CSS-GOTO]".
        if (target != null || CssModuleClassNavigation.isMemberAccessLeaf(element)) {
            log.warn(
                "[CSS-GOTO] el='${runCatching { element.text?.take(40) }.getOrNull()}' " +
                    "-> ${target?.containingFile?.name ?: "null"}",
            )
        }
        return target?.let { arrayOf(it) }
    }
}
