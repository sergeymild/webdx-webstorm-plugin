package com.intch.cssmodules

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssClass
import com.intellij.psi.util.PsiTreeUtil

/**
 * Go-to-declaration for `styles.<class>` that points at the SINGLE effective
 * declaration of the class. When the class is redefined in the importing module
 * itself (a local override of an `@import`-ed class), navigation lands on the
 * local declaration — the one whose properties win the Sass cascade — instead of
 * the imported source. Otherwise it lands on the file that actually declares it.
 *
 * Resolution is from source / generic PSI only (no TypeScript service), via
 * [CssModules.collectClassOrigins] (own file wins on a name clash).
 *
 * NOTE: the platform merges targets from every go-to provider; if the TS service
 * also resolves `styles.<class>`, its targets are added alongside this one.
 */
class CssModuleGotoDeclarationHandler : GotoDeclarationHandler {

    private val log = logger<CssModuleGotoDeclarationHandler>()

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val file = element.containingFile ?: return null
        // DIAGNOSTIC: log every invocation on a JS-like file so we can see whether the
        // handler runs and where it bails. Grep idea.log for "[CSS-GOTO]". Remove later.
        if (CssModules.isJsLikeFileName(file.name)) {
            log.warn(
                "[CSS-GOTO] invoked: file=${file.name} el=${element.javaClass.simpleName} " +
                    "text='${runCatching { element.text?.take(40) }.getOrNull()}' leaf=${element.firstChild == null}",
            )
        }

        if (element.firstChild != null) return null // leaves only
        if (!CssModules.isJsLikeFileName(file.name)) return null

        val name = element.text
        if (name.isEmpty() || !name.first().isJavaIdentifierStart()) {
            log.warn("[CSS-GOTO] bail: not an identifier leaf (name='$name')")
            return null
        }

        val dot = CssModules.prevMeaningfulLeaf(element)
        if (dot?.text != ".") {
            log.warn("[CSS-GOTO] bail: prev leaf is not '.' (was '${dot?.text}') for name='$name'")
            return null
        }
        val qualifier = CssModules.prevMeaningfulLeaf(dot) ?: return null
        val binding = qualifier.text
        if (binding.isEmpty() || !binding.first().isJavaIdentifierStart()) {
            log.warn("[CSS-GOTO] bail: qualifier not an identifier (binding='$binding')")
            return null
        }

        val moduleFile = CssModules.resolveModuleForBinding(file, binding)
        if (moduleFile == null) {
            log.warn(
                "[CSS-GOTO] bail: resolveModuleForBinding('$binding') == null in ${file.name}. " +
                    "Likely a non-relative import path (e.g. '@/...' alias) that resolveModuleForBinding " +
                    "does not handle.",
            )
            return null
        }

        val origins = CssModules.collectClassOrigins(moduleFile)
        val declaringFile = origins[name]
        if (declaringFile == null) {
            log.warn(
                "[CSS-GOTO] bail: class '$name' not found among ${origins.size} classes of " +
                    "${moduleFile.name} (keys sample=${origins.keys.take(10)})",
            )
            return null
        }

        val cssClass = PsiTreeUtil.collectElementsOfType(declaringFile, CssClass::class.java)
            .firstOrNull { it.name?.removePrefix(".") == name }
        if (cssClass == null) {
            log.warn("[CSS-GOTO] bail: no CssClass PSI for '$name' in ${declaringFile.name}")
            return null
        }

        log.warn(
            "[CSS-GOTO] RESOLVED '$binding.$name' -> ${declaringFile.name} " +
                "(module=${moduleFile.name}); returning 1 target",
        )
        return arrayOf(cssClass)
    }
}
