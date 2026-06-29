package com.webdx.cssmodules

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.css.CssSimpleSelector
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil

/**
 * Resolves "bam"-style SCSS-module class selectors that the CSS parser leaves as
 * raw `CssSimpleSelector`s with no `CssClass` node: a string `$var` used as a
 * selector via `#{$var}` interpolation, plus `&` BEM concatenation (`&__el`,
 * `&--mod`). Reads top-level string `$var` values by regex (no Sass-plugin PSI
 * dependency) and walks the `CssRuleset` nesting, resolving `#{$var}` /
 * `#{ns.$var}` -> the variable's value and `&` -> the parent ruleset's resolved
 * selector.
 *
 * Variables come from this file AND from files it DIRECTLY `@import`s / `@use`s:
 * bare (`@import`, `@use … as *`) -> `#{$var}`; default-namespaced (`@use 'vars'`)
 * -> `#{vars.$var}`; aliased (`@use … as v`) -> `#{v.$var}`.
 *
 * Scope: top-level string variables in this file or a directly imported file; `&`
 * nesting against an interpolated OR literal (`.foo`) parent; any nesting depth.
 * Out of scope (selector simply omitted, never guessed): transitive/`@forward`
 * variable reach, `@each`/`@for`, Sass maps, non-string values.
 */
internal object BamSelectors {

    // Top-level `$name: '.value';` (single- or double-quoted). Mirrors the regex
    // style of CssModules.scssImportPaths / scssDefinedSymbols.
    private val STRING_VAR = Regex("""(?m)^\s*\$([\w-]+)\s*:\s*['"]([^'"]+)['"]\s*;?""")
    // `#{$name}` or `#{ns.$name}`.
    private val INTERP = Regex("""#\{\s*(?:([A-Za-z_][\w-]*)\.)?\$([\w-]+)\s*}""")
    private val CLASS_TOKEN = Regex("""\.([A-Za-z0-9_-]+)""")
    // `@use 'path' [as alias|*]`.
    private val SCSS_USE = Regex("""@use\s+['"]([^'"]+)['"](?:\s+as\s+([\w*-]+))?""")
    // `@import '<one or more quoted paths>'`.
    private val SCSS_IMPORT = Regex("""@import\s+([^;{}\n]*)""")
    private val QUOTED = Regex("""['"]([^'"]+)['"]""")

    /**
     * Subject class name -> EVERY `CssSimpleSelector` that declares it, in document order.
     * A bam class is commonly declared at several sites (e.g. `#{$sidebar}__x` inside a
     * modifier block AND a top-level `&__x`); all are returned so the unused-class
     * inspection can grey each one, mirroring how a literal `.foo` declared twice is
     * flagged at both occurrences. Callers that want a single navigation target use the
     * first element. Cached.
     */
    fun bamClassDeclarations(moduleFile: PsiFile): Map<String, List<PsiElement>> =
        CachedValuesManager.getCachedValue(moduleFile) {
            CachedValueProvider.Result.create(
                compute(moduleFile),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    /** Subject class name of the bam ruleset the [element] sits in, or null. */
    fun bamClassForElement(element: PsiElement): String? {
        val file = element.containingFile?.originalFile ?: return null
        val ruleset = PsiTreeUtil.getParentOfType(element, CssRuleset::class.java, false) ?: return null
        val subject = subjectSelectorOf(ruleset) ?: return null
        return bamClassDeclarations(file).entries.firstOrNull { (_, els) -> els.any { it === subject } }?.key
    }

    private fun compute(file: PsiFile): Map<String, List<PsiElement>> {
        val vars = collectVariables(file)
        val out = LinkedHashMap<String, MutableList<PsiElement>>()
        for (ruleset in PsiTreeUtil.collectElementsOfType(file, CssRuleset::class.java)) {
            val selectors = ruleset.selectors
            // @include/@media container declares no class (its first selector starts with `@`).
            if (selectors.firstOrNull()?.text?.trimStart()?.startsWith("@") != false) continue
            // Resolve EVERY comma-group member, not just the first: a BEM class declared only
            // as a non-first member (`&__a, &__b {}`) must still be collected.
            for (selector in selectors) {
                val selText = selector.text
                // Skip plain class selectors — only the `&` / `#{}` forms escape regular
                // CssClass PSI and need this resolver.
                if (!selText.contains('&') && !selText.contains("#{")) continue
                val resolved = resolveSelectorText(selText, ruleset, vars, 0) ?: continue
                val cls = subjectClass(resolved) ?: continue
                val subject = PsiTreeUtil.getChildrenOfTypeAsList(selector, CssSimpleSelector::class.java)
                    .lastOrNull() ?: continue
                out.getOrPut(cls) { mutableListOf() }.add(subject)
            }
        }
        return out
    }

    /** Local string vars (bare keys) + string vars from directly imported SCSS files. */
    private fun collectVariables(file: PsiFile): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in STRING_VAR.findAll(file.text)) out.putIfAbsent(m.groupValues[1], m.groupValues[2])

        val dir = file.virtualFile?.parent ?: return out
        val project = file.project
        for ((path, namespace) in scssVarImports(file.text)) {
            val vf = resolveScssImport(dir, project, path) ?: continue
            val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: continue
            for (m in STRING_VAR.findAll(text)) {
                val key = if (namespace == null) m.groupValues[1] else "$namespace.${m.groupValues[1]}"
                out.putIfAbsent(key, m.groupValues[2])
            }
        }
        return out
    }

    /** Each `@use`/`@import` as (path, namespace?). namespace == null means bare/global. */
    private fun scssVarImports(text: String): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        for (m in SCSS_USE.findAll(text)) {
            val path = m.groupValues[1]
            val asName = m.groupValues[2]
            val namespace = when {
                asName.isEmpty() -> defaultNamespace(path) // `@use 'vars'` -> `vars`
                asName == "*" -> null                       // `@use 'vars' as *` -> global
                else -> asName                              // `@use 'vars' as v` -> `v`
            }
            out.add(path to namespace)
        }
        for (m in SCSS_IMPORT.findAll(text)) {
            for (q in QUOTED.findAll(m.groupValues[1])) out.add(q.groupValues[1] to null)
        }
        return out
    }

    /** `@use` default namespace: basename without directory, extension, or leading `_`. */
    private fun defaultNamespace(path: String): String =
        path.substringAfterLast('/').substringBeforeLast('.').removePrefix("_")

    /** Resolve a `@use`/`@import` path, trying Sass partial conventions when needed. */
    private fun resolveScssImport(dir: VirtualFile, project: Project, path: String): VirtualFile? {
        CssModules.resolveImportPath(dir, project, path)?.let { return it }
        val parent = path.substringBeforeLast('/', "")
        val base = path.substringAfterLast('/')
        for (candidate in listOf("$base.scss", "_$base.scss", "$base.sass", "_$base.sass")) {
            val p = if (parent.isEmpty()) candidate else "$parent/$candidate"
            CssModules.resolveImportPath(dir, project, p)?.let { return it }
        }
        return null
    }

    /**
     * Resolved text of [ruleset]'s first selector: `#{$var}`/`#{ns.$var}` substituted,
     * `&` -> parent. `@include`/`@media`/`@supports` containers (selector text starts
     * with `@`, or an empty named-selector list) are transparent: they contribute no
     * selector of their own and resolve to their parent's selector, so a `&__el` nested
     * inside such a block still concatenates onto the enclosing style rule. Walks the
     * `.parent` chain via `getParentOfType` (the lazy SCSS PSI returns null from
     * `getContext()` for these inner rulesets, so context-based walking must NOT be used).
     */
    private fun resolveRuleset(ruleset: CssRuleset, vars: Map<String, String>, depth: Int): String? {
        if (depth > 64) return null // nesting-depth backstop
        val selectorText = ruleset.selectors.firstOrNull()?.text
        if (selectorText == null || selectorText.trimStart().startsWith("@")) {
            val parent = PsiTreeUtil.getParentOfType(ruleset, CssRuleset::class.java, true) ?: return null
            return resolveRuleset(parent, vars, depth + 1)
        }
        return resolveSelectorText(selectorText, ruleset, vars, depth)
    }

    /**
     * Resolve one selector's text (`#{$var}` substituted, `&` -> the parent ruleset's resolved
     * FIRST selector). Used per comma-group member; `&` parent resolution intentionally folds
     * onto the parent's first selector (a parent comma group is rare and ambiguous).
     */
    private fun resolveSelectorText(
        selText: String,
        ruleset: CssRuleset,
        vars: Map<String, String>,
        depth: Int,
    ): String? {
        if (depth > 64) return null
        var text = substituteInterpolations(selText, vars) ?: return null
        if (text.contains('&')) {
            val parent = PsiTreeUtil.getParentOfType(ruleset, CssRuleset::class.java, true) ?: return null
            val parentResolved = resolveRuleset(parent, vars, depth + 1) ?: return null
            text = text.replace("&", parentResolved)
        }
        return text
    }

    /** Replace every `#{$var}` / `#{ns.$var}` with its value; null if any is unknown. */
    private fun substituteInterpolations(text: String, vars: Map<String, String>): String? {
        var unresolved = false
        val result = INTERP.replace(text) { m ->
            val ns = m.groupValues[1]
            val name = m.groupValues[2]
            val key = if (ns.isEmpty()) name else "$ns.$name"
            vars[key] ?: run { unresolved = true; "" }
        }
        return if (unresolved) null else result
    }

    /** The class a compound DECLARES: a class token that is the tail of the compound. */
    private fun subjectClass(resolved: String): String? {
        val s = resolved.trim()
        val m = CLASS_TOKEN.findAll(s).lastOrNull() ?: return null
        // Only a declaration when the class is the subject (nothing — pseudo/combinator —
        // follows it): `.a:after` and `.a .b__c`-context tokens are not subjects.
        if (m.range.last != s.length - 1) return null
        // Reject a class in a descendant/combinator context (e.g. `.a .child`, `.a > .child`) —
        // only a class fused to the parent (BEM `__`/`--`) or starting the compound is a subject.
        if (m.range.first > 0) {
            val before = s[m.range.first - 1]
            if (before.isWhitespace() || before == '>' || before == '~' || before == '+') return null
        }
        return m.groupValues[1]
    }

    /** The subject `CssSimpleSelector` of a ruleset: the last simple selector of its first selector. */
    private fun subjectSelectorOf(ruleset: CssRuleset): CssSimpleSelector? {
        val selector = ruleset.selectors.firstOrNull() ?: return null
        return PsiTreeUtil.getChildrenOfTypeAsList(selector, CssSimpleSelector::class.java).lastOrNull()
    }
}
