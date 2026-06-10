package com.intch.cssmodules

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.PsiNavigateUtil

/**
 * Overrides the built-in `GotoDeclaration` action (registered with overrides="true")
 * to intercept `styles.<class>` navigation. The TypeScript-Go service resolves that
 * member to EVERY same-named CSS declaration (and bypasses the gotoDeclarationHandler
 * / directNavigationProvider extension points), so we take over at the action level:
 * if the caret is on a `styles.<class>` we can resolve to a single effective
 * declaration (local override wins), we navigate there ourselves and stop. For
 * everything else we delegate to the platform's default behavior.
 *
 * Wrapped in try/catch so a failure here can never break normal Go to Declaration —
 * we always fall through to super.
 */
class CssModuleGotoDeclarationAction : GotoDeclarationAction() {

    private val log = logger<CssModuleGotoDeclarationAction>()

    override fun actionPerformed(e: AnActionEvent) {
        try {
            val editor = e.getData(CommonDataKeys.EDITOR)
            val psiFile = e.getData(CommonDataKeys.PSI_FILE)
            if (editor != null && psiFile != null) {
                val offset = editor.caretModel.offset
                val element = psiFile.findElementAt(offset)
                if (element != null && CssModuleClassNavigation.isMemberAccessLeaf(element)) {
                    val target = CssModuleClassNavigation.resolveTarget(element)
                    log.warn(
                        "[CSS-GOTOACTION] '${element.text}' -> ${target?.containingFile?.name ?: "null (delegating)"}",
                    )
                    if (target != null) {
                        PsiNavigateUtil.navigate(target)
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            log.warn("[CSS-GOTOACTION] error, delegating to default", t)
        }
        super.actionPerformed(e)
    }
}
