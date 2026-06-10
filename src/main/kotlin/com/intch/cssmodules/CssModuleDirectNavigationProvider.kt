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
        // DIAGNOSTIC (temporary): log every time this provider is consulted on an element
        // whose text mentions a class we care about, so we can see the real element SHAPE
        // the platform passes here. Grep idea.log for "[CSS-DIRECTNAV]".
        val file = element.containingFile
        if (file != null && CssModules.isJsLikeFileName(file.name)) {
            val txt = runCatching { element.text }.getOrNull()
            if (txt != null && txt.length <= 60 && txt.contains("nextButton")) {
                val prev = runCatching { CssModules.prevMeaningfulLeaf(element)?.text }.getOrNull()
                log.warn(
                    "[CSS-DIRECTNAV] entry: cls=${element.javaClass.simpleName} " +
                        "leaf=${element.firstChild == null} text='${txt.take(40)}' prev='$prev' " +
                        "parent=${element.parent?.javaClass?.simpleName}",
                )
            }
        }

        val target = CssModuleClassNavigation.resolveTarget(element)
        if (target != null) {
            log.warn("[CSS-DIRECTNAV] RESOLVED -> ${target.containingFile?.name}")
        }
        return target
    }
}
