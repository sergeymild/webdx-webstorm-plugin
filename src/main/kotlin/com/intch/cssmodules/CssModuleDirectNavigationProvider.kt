package com.intch.cssmodules

import com.intellij.navigation.DirectNavigationProvider
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement

/**
 * Direct navigation for `styles.<class>`. This is the extension point the modern
 * Ctrl+Click / Ctrl+B path (`GotoDeclarationOrUsageHandler2`) consults FIRST: a
 * non-null result here short-circuits the platform's Symbol navigation — i.e. the
 * TypeScript-Go service's own resolution that otherwise offers every same-named CSS
 * declaration. So navigating `styles.nextButton` lands on the single effective
 * declaration (the local override, else the `@import` source) without a chooser.
 */
class CssModuleDirectNavigationProvider : DirectNavigationProvider {

    private val log = logger<CssModuleDirectNavigationProvider>()

    override fun getNavigationElement(element: PsiElement): PsiElement? {
        val target = CssModuleClassNavigation.resolveTarget(element)
        // DIAGNOSTIC (temporary): log when this fires on a member-access position.
        // Grep idea.log for "[CSS-DIRECTNAV]". getNavigationElement runs on hover too,
        // so only log the member-access shape to keep noise down.
        if (target != null || CssModuleClassNavigation.isMemberAccessLeaf(element)) {
            log.warn(
                "[CSS-DIRECTNAV] el='${runCatching { element.text?.take(40) }.getOrNull()}' " +
                    "-> ${target?.containingFile?.name ?: "null"}",
            )
        }
        return target
    }
}
