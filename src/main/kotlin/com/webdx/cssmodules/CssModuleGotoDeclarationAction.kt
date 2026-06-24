package com.webdx.cssmodules

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.find.actions.ShowUsagesAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
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

    init {
        // We register with overrides="true" against the built-in GotoDeclaration, which
        // appears in the Main Menu. Newer platforms require non-empty action text for any
        // menu item, so set it explicitly here to avoid an "Empty menu item text" crash
        // when the menu is built.
        templatePresentation.text = "Go to Declaration"
    }

    override fun actionPerformed(e: AnActionEvent) {
        try {
            val editor = e.getData(CommonDataKeys.EDITOR)
            val psiFile = e.getData(CommonDataKeys.PSI_FILE)
            if (editor != null && psiFile != null) {
                val offset = editor.caretModel.offset
                val element = psiFile.findElementAt(offset)
                if (element != null) {
                    if (CssModuleClassNavigation.isMemberAccessLeaf(element)) {
                        val target = CssModuleClassNavigation.resolveTarget(element)
                        log.warn(
                            "[CSS-GOTOACTION] '${element.text}' -> ${target?.containingFile?.name ?: "null (delegating)"}",
                        )
                        if (target != null) {
                            PsiNavigateUtil.navigate(target)
                            return
                        }
                    }
                    // SCSS `@extend .class`: navigate to the extended class's declaration
                    // (resolved through the module's @import graph).
                    val extendTarget = CssModuleClassNavigation.resolveExtendTarget(element)
                    if (extendTarget != null) {
                        PsiNavigateUtil.navigate(extendTarget)
                        return
                    }
                    // React Native StyleSheet styles: styles.<key> or a destructured local.
                    val rnTarget = com.webdx.rnstyles.RnStyles.resolveKeyProperty(element)
                    if (rnTarget != null) {
                        PsiNavigateUtil.navigate(rnTarget)
                        return
                    }
                    // SCSS symbol DECLARATION (`$var:` / `@function`/`@mixin`/`%ph`): Cmd+Click
                    // shows its usages via the native "Show Usages" popup (code preview + grouping),
                    // instead of the contextless go-to "Choose Declaration" list.
                    if (com.webdx.scsssymbols.ScssSymbols.isDeclarationAt(element)) {
                        val showUsages = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                            .getAction("ShowUsages")
                        if (showUsages != null) {
                            showUsages.actionPerformed(e)
                            return
                        }
                    }
                    // CSS-module class DECLARATION (`.foo` / bam `&__x` / camelCase `&Prev`):
                    // Cmd+Click shows its usages (scoped Find Usages), so a class applied only
                    // dynamically (`styles[variant]`) still has a click target — the usages popup
                    // jumps straight to the access site when there is exactly one.
                    //
                    // We call startFindUsages with the caret element EXPLICITLY rather than
                    // delegating to the ShowUsages action: a bam selector (`&Prev`, `&__x`) has no
                    // CssClass PSI and isn't a resolvable reference, so the platform's caret->target
                    // resolution finds nothing and the action would silently no-op. Passing the leaf
                    // straight to our (order="first") find-usages handler sidesteps that.
                    if (CssModuleClassNavigation.isModuleClassDeclarationLeaf(element)) {
                        showUsagesFor(element, editor)
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            log.warn("[CSS-GOTOACTION] error, delegating to default", t)
        }
        super.actionPerformed(e)
    }

    /**
     * Opens the Show Usages popup for [element] itself — not for whatever the platform would
     * resolve at the caret. Bam selectors (`&Prev`, `&__x`) declare a class with no CssClass
     * PSI and no reference, so only an explicit target reaches our scoped find-usages handler.
     */
    private fun showUsagesFor(element: PsiElement, editor: Editor) {
        val point = JBPopupFactory.getInstance().guessBestPopupLocation(editor)
        ShowUsagesAction.startFindUsages(element, point, editor)
    }
}
