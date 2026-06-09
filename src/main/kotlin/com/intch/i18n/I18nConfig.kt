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

    /** Variable names auto-provided by the i18n config (never required at call sites). */
    fun defaultVariables(project: Project): Set<String> {
        val initVf = initFile(project) ?: return emptySet()
        val text = runCatching { VfsUtilCore.loadText(initVf) }.getOrNull() ?: return emptySet()
        return defaultVariableNames(text)
    }

    private fun fromConfig(project: Project): VirtualFile? {
        val initVf = initFile(project) ?: return null
        val text = runCatching { VfsUtilCore.loadText(initVf) }.getOrNull() ?: return null
        val path = enImportPath(text) ?: return null
        val target = resolveRelative(initVf.parent, path)
        return target?.takeIf { it.name.endsWith(".json", ignoreCase = true) }
    }

    /** The file that wires up i18n (imports `initReactI18next`), or null. */
    private fun initFile(project: Project): VirtualFile? {
        val scope = GlobalSearchScope.projectScope(project)
        for (ext in JS_EXTS) {
            for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: continue
                if (text.contains("initReactI18next")) return vf
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

    /** Names declared in `interpolation.defaultVariables { … }` — auto-provided, never required. */
    fun defaultVariableNames(initFileText: String): Set<String> {
        val block = DEFAULT_VARS_BLOCK.find(initFileText)?.groupValues?.get(1) ?: return emptySet()
        return IDENTIFIER_KEY.findAll(block).map { it.groupValues[1] }.toSet()
    }

    private val EN_IMPORT = Regex("""import\s+en\s+from\s+['"]([^'"]+)['"]""")
    private val DEFAULT_VARS_BLOCK = Regex("""defaultVariables\s*:\s*\{([^}]*)\}""")
    private val IDENTIFIER_KEY = Regex("""(\w+)\s*:""")
}
