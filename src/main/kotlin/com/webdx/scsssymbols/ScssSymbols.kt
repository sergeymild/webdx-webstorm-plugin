package com.webdx.scsssymbols

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * SCSS symbol declarations and references, detected by regex over file text (no
 * Sass-plugin PSI dependency).
 *
 * The text-level scans ([declarationsText] / [referencesText]) return name + offset only
 * and are cheap — the project-wide resolution (ScssSymbolsResolve.kt) uses them over raw
 * file text so it never loads the PSI/AST of the whole project. The PSI variants
 * ([declarationsIn] / [referencesIn]) add `findElementAt` to attach a navigable element
 * and are used only on a single, already-loaded file (the current editor file).
 */
internal object ScssSymbols {

    enum class Kind { VARIABLE, FUNCTION, MIXIN, PLACEHOLDER }

    /** A declaration hit at the text level: bare [name] (no `$`/`%`), [kind], name-token [offset]. */
    data class DeclHit(val name: String, val kind: Kind, val offset: Int)

    /** A reference hit at the text level: bare [name], [kind], optional [namespace], name-token [offset]. */
    data class RefHit(val name: String, val kind: Kind, val namespace: String?, val offset: Int)

    /** A declaration with a navigable element: bare [name], [kind], name-token [element], its [file]. */
    data class Decl(val name: String, val kind: Kind, val element: PsiElement, val file: PsiFile)

    /** A reference with a navigable element: bare [name], [kind], optional [namespace], use [element], its [file]. */
    data class Ref(val name: String, val kind: Kind, val namespace: String?, val element: PsiElement, val file: PsiFile)

    private val VAR_DECL = Regex("""(?m)^[ \t]*\$([\w-]+)\s*:""")
    private val FUNC_DECL = Regex("""@function\s+([\w-]+)""")
    private val MIXIN_DECL = Regex("""@mixin\s+([\w-]+)""")
    private val PLACEHOLDER_DECL = Regex("""%([\w-]+)\s*[{,]""")

    // ns. prefix is captured in group 1 (optional); the name in group 2.
    private val VAR_REF = Regex("""(?:([\w-]+)\.)?\$([\w-]+)""")
    private val FUNC_REF = Regex("""(?:([\w-]+)\.)?([\w-]+)\s*\(""")
    private val MIXIN_REF = Regex("""@include\s+(?:([\w-]+)\.)?([\w-]+)""")
    private val PLACEHOLDER_REF = Regex("""@extend\s+(?:([\w-]+)\.)?%([\w-]+)""")

    /** All declaration hits in raw SCSS [text] (no PSI). */
    fun declarationsText(text: String): List<DeclHit> {
        val out = ArrayList<DeclHit>()
        fun collect(re: Regex, kind: Kind) {
            for (m in re.findAll(text)) {
                out.add(DeclHit(m.groupValues[1], kind, m.groups[1]!!.range.first))
            }
        }
        collect(VAR_DECL, Kind.VARIABLE)
        collect(FUNC_DECL, Kind.FUNCTION)
        collect(MIXIN_DECL, Kind.MIXIN)
        collect(PLACEHOLDER_DECL, Kind.PLACEHOLDER)
        return out
    }

    /** All reference hits in raw SCSS [text] (no PSI), with namespace prefixes captured. */
    fun referencesText(text: String): List<RefHit> {
        val out = ArrayList<RefHit>()
        // declaration LHS `$name:` offsets — excluded from variable references.
        val varDeclStarts = VAR_DECL.findAll(text).map { it.groups[1]!!.range.first }.toSet()

        for (m in VAR_REF.findAll(text)) {
            val nameStart = m.groups[2]!!.range.first
            if (nameStart in varDeclStarts) continue // declaration LHS, not a use
            out.add(RefHit(m.groupValues[2], Kind.VARIABLE, m.groupValues[1].ifEmpty { null }, nameStart))
        }
        for (m in FUNC_REF.findAll(text)) {
            // skip declarations and mixin includes that also match `name(`
            val before = text.substring(maxOf(0, m.range.first - 12), m.range.first)
            if (before.endsWith("@function ") || before.endsWith("@mixin ") || before.endsWith("@include ")) continue
            out.add(RefHit(m.groupValues[2], Kind.FUNCTION, m.groupValues[1].ifEmpty { null }, m.groups[2]!!.range.first))
        }
        for (m in MIXIN_REF.findAll(text)) {
            out.add(RefHit(m.groupValues[2], Kind.MIXIN, m.groupValues[1].ifEmpty { null }, m.groups[2]!!.range.first))
        }
        for (m in PLACEHOLDER_REF.findAll(text)) {
            out.add(RefHit(m.groupValues[2], Kind.PLACEHOLDER, m.groupValues[1].ifEmpty { null }, m.groups[2]!!.range.first))
        }
        return out
    }

    /** All symbol declarations in [file], each with a navigable PSI element. */
    fun declarationsIn(file: PsiFile): List<Decl> =
        declarationsText(file.text).mapNotNull { hit ->
            file.findElementAt(hit.offset)?.let { Decl(hit.name, hit.kind, it, file) }
        }

    /** All symbol references in [file], each with a navigable PSI element. */
    fun referencesIn(file: PsiFile): List<Ref> =
        referencesText(file.text).mapNotNull { hit ->
            file.findElementAt(hit.offset)?.let { Ref(hit.name, hit.kind, hit.namespace, it, file) }
        }

    /** The reference whose name-token contains [element]'s offset, or null. */
    fun referenceAt(element: PsiElement): Ref? {
        val file = element.containingFile?.originalFile ?: return null
        val offset = element.textOffset
        return referencesIn(file).firstOrNull { it.element.textRange.contains(offset) }
    }
}
