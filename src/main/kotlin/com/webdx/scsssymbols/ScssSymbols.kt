package com.webdx.scsssymbols

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * SCSS symbol declarations and references, detected by regex over file text (no
 * Sass-plugin PSI dependency); PSI elements are located via `findElementAt` so they can
 * be highlighted / navigated. The [resolve] / [usesByDeclaration] resolution lives in a
 * separate file (ScssSymbolsResolve.kt) on top of [ScssImportGraph].
 */
internal object ScssSymbols {

    enum class Kind { VARIABLE, FUNCTION, MIXIN, PLACEHOLDER }

    /** A declaration: bare [name] (no `$`/`%`), [kind], the name-token [element], and its [file]. */
    data class Decl(val name: String, val kind: Kind, val element: PsiElement, val file: PsiFile)

    private val VAR_DECL = Regex("""(?m)^[ \t]*\$([\w-]+)\s*:""")
    private val FUNC_DECL = Regex("""@function\s+([\w-]+)""")
    private val MIXIN_DECL = Regex("""@mixin\s+([\w-]+)""")
    private val PLACEHOLDER_DECL = Regex("""%([\w-]+)\s*[{,]""")

    /** All symbol declarations in [file]. The name capture group's start is used to anchor the PSI element. */
    fun declarationsIn(file: PsiFile): List<Decl> {
        val text = file.text
        val out = ArrayList<Decl>()
        fun collect(re: Regex, kind: Kind) {
            for (m in re.findAll(text)) {
                val nameRange = m.groups[1]!!.range
                val element = file.findElementAt(nameRange.first) ?: continue
                out.add(Decl(m.groupValues[1], kind, element, file))
            }
        }
        collect(VAR_DECL, Kind.VARIABLE)
        collect(FUNC_DECL, Kind.FUNCTION)
        collect(MIXIN_DECL, Kind.MIXIN)
        collect(PLACEHOLDER_DECL, Kind.PLACEHOLDER)
        return out
    }
}
