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

    /** A reference: bare [name], [kind], optional [namespace] (`ns.` prefix), the use [element], and its [file]. */
    data class Ref(val name: String, val kind: Kind, val namespace: String?, val element: PsiElement, val file: PsiFile)

    // ns. prefix is captured in group 1 (optional); the name in group 2.
    private val VAR_REF = Regex("""(?:([\w-]+)\.)?\$([\w-]+)""")
    private val FUNC_REF = Regex("""(?:([\w-]+)\.)?([\w-]+)\s*\(""")
    private val MIXIN_REF = Regex("""@include\s+(?:([\w-]+)\.)?([\w-]+)""")
    private val PLACEHOLDER_REF = Regex("""@extend\s+(?:([\w-]+)\.)?%([\w-]+)""")

    /** All symbol references in [file] (every use site), with namespace prefixes captured. */
    fun referencesIn(file: PsiFile): List<Ref> {
        val text = file.text
        val out = ArrayList<Ref>()
        // declaration LHS `$name:` offsets — excluded from variable references.
        val varDeclStarts = VAR_DECL.findAll(text).map { it.groups[1]!!.range.first }.toSet()

        for (m in VAR_REF.findAll(text)) {
            val nameStart = m.groups[2]!!.range.first
            if (nameStart in varDeclStarts) continue // it's a declaration LHS, not a use
            val element = file.findElementAt(nameStart) ?: continue
            out.add(Ref(m.groupValues[2], Kind.VARIABLE, m.groupValues[1].ifEmpty { null }, element, file))
        }
        for (m in FUNC_REF.findAll(text)) {
            // skip declarations and mixin includes that also match `name(`
            val before = text.substring(maxOf(0, m.range.first - 12), m.range.first)
            if (before.endsWith("@function ") || before.endsWith("@mixin ") || before.endsWith("@include ")) continue
            val nameStart = m.groups[2]!!.range.first
            val element = file.findElementAt(nameStart) ?: continue
            out.add(Ref(m.groupValues[2], Kind.FUNCTION, m.groupValues[1].ifEmpty { null }, element, file))
        }
        for (m in MIXIN_REF.findAll(text)) {
            val nameStart = m.groups[2]!!.range.first
            val element = file.findElementAt(nameStart) ?: continue
            out.add(Ref(m.groupValues[2], Kind.MIXIN, m.groupValues[1].ifEmpty { null }, element, file))
        }
        for (m in PLACEHOLDER_REF.findAll(text)) {
            val nameStart = m.groups[2]!!.range.first
            val element = file.findElementAt(nameStart) ?: continue
            out.add(Ref(m.groupValues[2], Kind.PLACEHOLDER, m.groupValues[1].ifEmpty { null }, element, file))
        }
        return out
    }

    /** The reference whose name-token contains [element]'s offset, or null. */
    fun referenceAt(element: PsiElement): Ref? {
        val file = element.containingFile?.originalFile ?: return null
        val offset = element.textOffset
        return referencesIn(file).firstOrNull { it.element.textRange.contains(offset) }
    }
}
