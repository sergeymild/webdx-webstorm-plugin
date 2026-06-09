package com.intch.i18n

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/** Locates the JSON file that defines the valid i18n keys, driven by the i18n config. */
internal object I18nConfig {

    private val JS_EXTS = listOf("ts", "tsx", "js", "jsx", "mts", "cts", "mjs", "cjs")

    /**
     * The locale JSON whose keys are the source of truth, or null if none is found.
     * Primary: follow the `en` import from the i18n init file. Fallback: a file named
     * `en.json` by convention (preferring one under a `translations`/`lang` directory).
     */
    fun findKeySource(project: Project): VirtualFile? =
        fromConfig(project) ?: byConvention(project)

    private fun fromConfig(project: Project): VirtualFile? {
        val scope = GlobalSearchScope.projectScope(project)
        for (ext in JS_EXTS) {
            for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: continue
                if (!text.contains("initReactI18next")) continue
                val path = enImportPath(text) ?: continue
                val target = resolveRelative(vf.parent, path)
                if (target != null && target.name.endsWith(".json", ignoreCase = true)) return target
            }
        }
        return null
    }

    private fun byConvention(project: Project): VirtualFile? {
        val scope = GlobalSearchScope.projectScope(project)
        val candidates = FilenameIndex.getVirtualFilesByName("en.json", scope)
        return candidates.firstOrNull { underLocaleDir(it) } ?: candidates.firstOrNull()
    }

    private fun underLocaleDir(vf: VirtualFile): Boolean {
        val parentNames = generateSequence(vf.parent) { it.parent }.take(3).map { it.name.lowercase() }
        return parentNames.any { it == "translations" || it == "lang" || it == "locales" }
    }

    private fun resolveRelative(from: VirtualFile?, path: String): VirtualFile? {
        var cur: VirtualFile? = from ?: return null
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

    /**
     * Path of the JSON imported under the local name `en` in the given file text
     * (`import en from './translations/en.json'` -> `./translations/en.json`), or
     * null if there is no such import.
     */
    fun enImportPath(initFileText: String): String? =
        EN_IMPORT.find(initFileText)?.groupValues?.get(1)

    private val EN_IMPORT = Regex("""import\s+en\s+from\s+['"]([^'"]+)['"]""")
}
