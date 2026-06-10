package com.intch.cssmodules

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.css.CssClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/** Shared CSS-module helpers used by completion, the unused-class inspection, etc. */
internal object CssModules {

    private val SCSS_IMPORT = Regex("""@(?:import|use|forward)\b([^;{}\n]*)""")
    private val QUOTED = Regex("""['"]([^'"]+)['"]""")

    /** All paths referenced by `@import`/`@use`/`@forward` in SCSS [text], in order. */
    fun scssImportPaths(text: String): List<String> =
        SCSS_IMPORT.findAll(text)
            .flatMap { m -> QUOTED.findAll(m.groupValues[1]).map { it.groupValues[1] } }
            .toList()

    fun isModuleFileName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".module.css") ||
            n.endsWith(".module.scss") ||
            n.endsWith(".module.sass") ||
            n.endsWith(".module.less")
    }

    fun isJsLikeFileName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".tsx") || n.endsWith(".ts") ||
            n.endsWith(".jsx") || n.endsWith(".js") ||
            n.endsWith(".mts") || n.endsWith(".cts") ||
            n.endsWith(".mjs") || n.endsWith(".cjs")
    }

    /** All class names declared in a CSS-module file (deduped, dot stripped). */
    fun collectClassNames(moduleFile: PsiFile): List<String> =
        PsiTreeUtil.collectElementsOfType(moduleFile, CssClass::class.java)
            .mapNotNull { it.name?.removePrefix(".")?.takeIf(String::isNotEmpty) }
            .distinct()

    /**
     * Find a CSS module imported in [jsFile] under the local name [binding]
     * (`import <binding> from './x.module.scss'`) and return its PsiFile.
     */
    fun resolveModuleForBinding(jsFile: PsiFile, binding: String): PsiFile? {
        val dir = jsFile.virtualFile?.parent ?: return null
        val regex = Regex("""import\s+${Regex.escape(binding)}\s+from\s+['"]([^'"]+)['"]""")
        val path = regex.find(jsFile.text)?.groupValues?.get(1) ?: return null
        if (!isModuleFileName(path)) return null
        val vf = resolveRelative(dir, path) ?: return null
        return PsiManager.getInstance(jsFile.project).findFile(vf)
    }

    /**
     * Every CSS-module import in [jsFile] as `localBinding -> {classNames}`.
     * e.g. `import styles from './X.module.scss'` -> "styles" -> {mobileWrapper, ...}.
     */
    fun cssModuleBindings(jsFile: PsiFile): Map<String, Set<String>> {
        val dir = jsFile.virtualFile?.parent ?: return emptyMap()
        val psiManager = PsiManager.getInstance(jsFile.project)
        val result = HashMap<String, Set<String>>()
        Regex("""import\s+(\w+)\s+from\s+['"]([^'"]+)['"]""").findAll(jsFile.text).forEach { m ->
            val binding = m.groupValues[1]
            val path = m.groupValues[2]
            if (!isModuleFileName(path)) return@forEach
            val vf = resolveRelative(dir, path) ?: return@forEach
            val psi = psiManager.findFile(vf) ?: return@forEach
            result[binding] = collectClassNames(psi).toSet()
        }
        return result
    }

    /** Files importing [moduleFile], each mapped to the local binding name(s) used. */
    fun findImporters(moduleFile: PsiFile): Map<PsiFile, Set<String>> {
        val project = moduleFile.project
        val map = LinkedHashMap<PsiFile, MutableSet<String>>()
        ReferencesSearch
            .search(moduleFile, GlobalSearchScope.projectScope(project))
            .forEach { ref ->
                val f = ref.element.containingFile ?: return@forEach
                val set = map.getOrPut(f) { linkedSetOf() }
                importBindingName(ref.element)?.let { set.add(it) }
            }
        return map
    }

    /** All class names actually referenced as `<binding>.<name>` across importing files. */
    fun collectUsedClassNames(moduleFile: PsiFile): Set<String> {
        val used = HashSet<String>()
        for ((file, bindings) in findImporters(moduleFile)) {
            PsiTreeUtil.collectElements(file) { it.firstChild == null && it.text == "." }
                .forEach { dot ->
                    val qualifier = prevMeaningfulLeaf(dot) ?: return@forEach
                    if (qualifier.text !in bindings) return@forEach
                    val member = nextMeaningfulLeaf(dot) ?: return@forEach
                    if (member.text.isNotEmpty() && member.text.first().isJavaIdentifierStart()) {
                        used.add(member.text)
                    }
                }
        }
        return used
    }

    fun prevMeaningfulLeaf(el: PsiElement): PsiElement? {
        var p = PsiTreeUtil.prevLeaf(el)
        while (p != null && (p is PsiWhiteSpace || p is PsiComment)) p = PsiTreeUtil.prevLeaf(p)
        return p
    }

    fun nextMeaningfulLeaf(el: PsiElement): PsiElement? {
        var p = PsiTreeUtil.nextLeaf(el)
        while (p != null && (p is PsiWhiteSpace || p is PsiComment)) p = PsiTreeUtil.nextLeaf(p)
        return p
    }

    private fun resolveRelative(from: VirtualFile, path: String): VirtualFile? {
        var cur: VirtualFile? = from
        for (part in path.split('/')) {
            cur = when (part) {
                "", "." -> cur
                ".." -> cur?.parent
                else -> cur?.findChild(part)
            }
            if (cur == null) return null
        }
        return cur
    }

    private fun importBindingName(refElement: PsiElement): String? {
        var cur: PsiElement? = refElement
        var depth = 0
        while (cur != null && depth < 12) {
            val text = cur.text
            if (text.startsWith("import")) {
                return Regex("""import\s+(\w+)""").find(text)?.groupValues?.get(1)
            }
            cur = cur.parent
            depth++
        }
        return null
    }
}
