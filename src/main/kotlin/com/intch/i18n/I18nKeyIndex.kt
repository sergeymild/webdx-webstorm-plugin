package com.intch.i18n

import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** Cached view over the project's locale JSON: the valid keys and their PSI targets. */
internal object I18nKeyIndex {

    /** The located key-source JSON as a PsiFile, or null if none is found. */
    fun keySourceFile(project: Project): PsiFile? =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val vf = I18nConfig.findKeySource(project)
            val psi = vf?.let { PsiManager.getInstance(project).findFile(it) }
            // Recompute when project PSI changes (init file edited, files added/removed).
            CachedValueProvider.Result.create(psi, PsiModificationTracker.MODIFICATION_COUNT)
        }

    /** All valid dot-path keys in the project's locale JSON. */
    fun keys(project: Project): Set<String> {
        val file = keySourceFile(project) ?: return emptySet()
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(I18nKeys.collectKeys(file), file)
        }
    }

    /** The JsonProperty defining [key], or null if it is not a valid key. */
    fun resolve(project: Project, key: String): JsonProperty? {
        val file = keySourceFile(project) ?: return null
        return I18nKeys.resolveProperty(file, key)
    }

    /** The `{{placeholder}}` names in [key]'s value (empty if the key is unknown). */
    fun placeholders(project: Project, key: String): Set<String> {
        val file = keySourceFile(project) ?: return emptySet()
        val value = I18nKeys.valueOf(file, key) ?: return emptySet()
        return I18nKeys.placeholdersOf(value)
    }

    /** Variable names auto-provided by `interpolation.defaultVariables` in the i18n config. */
    fun defaultVariables(project: Project): Set<String> = I18nConfig.defaultVariables(project)
}
