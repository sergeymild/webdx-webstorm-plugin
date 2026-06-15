package com.webdx.cssmodules

import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssClass
import com.intellij.psi.util.PsiTreeUtil

/**
 * Shared resolution for `styles.<class>` -> the single effective CSS class
 * declaration. When the importing module redefines an `@import`-ed class, the
 * local declaration (the Sass-cascade winner) is returned; otherwise the file in
 * the import chain that declares it. Resolved from source PSI only (no TS service),
 * via [CssModules.collectClassOrigins] (own file wins on a name clash).
 */
internal object CssModuleClassNavigation {

    private val log = logger<CssModuleClassNavigation>()

    /** [element] is the leaf under the caret. Returns the target CssClass or null. */
    fun resolveTarget(element: PsiElement): PsiElement? {
        if (element.firstChild != null) return null // leaves only
        val rawFile = element.containingFile ?: return null
        // During navigation/resolve the platform may hand us a non-physical PSI COPY
        // whose virtualFile is null; originalFile gives the physical file we can resolve
        // imports against (it has a virtualFile + the same text).
        val file = rawFile.originalFile
        if (!CssModules.isJsLikeFileName(file.name)) return null

        // `<binding>.<name>` (caret on the member) OR `<binding>['name']` (caret on the
        // string literal — needed for `--`-modifier classes that aren't valid identifiers).
        val (binding, name) = memberAccessAt(element) ?: return null

        val moduleFile = CssModules.resolveModuleForBinding(file, binding)
        if (moduleFile == null) {
            log.warn(
                "[CSS-NAV] bail: resolveModuleForBinding('$binding') == null in ${file.name} " +
                    "(name='$name') vf=${file.virtualFile?.name} rawVf=${rawFile.virtualFile?.name} " +
                    "import='${importLineFor(file.text, binding)}'",
            )
            return null
        }

        val origins = CssModules.collectClassOrigins(moduleFile)
        val declaringFile = origins[name]
        if (declaringFile == null) {
            log.warn(
                "[CSS-NAV] bail: '$name' not in collectClassOrigins(${moduleFile.name}); " +
                    "${origins.size} classes, sample=${origins.keys.take(12)}",
            )
            return null
        }

        val cssClass = PsiTreeUtil.collectElementsOfType(declaringFile, CssClass::class.java)
            .firstOrNull { it.name?.removePrefix(".") == name }
        val target = cssClass ?: BamSelectors.bamClassDeclarations(declaringFile)[name]?.firstOrNull()
        if (target == null) {
            log.warn("[CSS-NAV] bail: no CssClass or bam selector for '$name' in ${declaringFile.name}")
            return null
        }

        log.warn("[CSS-NAV] OK: '$binding.$name' -> ${declaringFile.name} (module=${moduleFile.name})")
        return target
    }

    /**
     * The `(binding, className)` of a CSS-module member access at the leaf [element], or
     * null. Handles dot access `<binding>.<name>` (caret on the member identifier) and
     * static bracket access `<binding>['name']` (caret on the string literal).
     */
    private fun memberAccessAt(element: PsiElement): Pair<String, String>? {
        // bracket: `<binding>['name']`
        CssModules.bracketMemberAccess(element)?.let { return it }
        // dot: `<binding>.<name>`
        val name = element.text
        if (name.isEmpty() || !name.first().isJavaIdentifierStart()) return null
        val dot = CssModules.prevMeaningfulLeaf(element) ?: return null
        if (dot.text != ".") return null
        val binding = CssModules.prevMeaningfulLeaf(dot)?.text ?: return null
        if (binding.isEmpty() || !binding.first().isJavaIdentifierStart()) return null
        return binding to name
    }

    private fun importLineFor(text: String, binding: String): String =
        Regex("""import\s+${Regex.escape(binding)}\s+from\s+['"][^'"]+['"]""")
            .find(text)?.value ?: "<no match>"

    /**
     * True when [element] is a CSS-module member-access leaf — either `<binding>.<name>`
     * (dot) or `<binding>['name']` (static bracket). Used by the GotoDeclaration action to
     * decide whether to attempt resolution, so it MUST cover bracket access for
     * `--`-modifier classes that can only be written with brackets.
     */
    fun isMemberAccessLeaf(element: PsiElement): Boolean {
        if (element.firstChild != null) return false
        if (CssModules.bracketMemberAccess(element) != null) return true
        val name = element.text
        if (name.isEmpty() || !name.first().isJavaIdentifierStart()) return false
        return CssModules.prevMeaningfulLeaf(element)?.text == "."
    }
}
