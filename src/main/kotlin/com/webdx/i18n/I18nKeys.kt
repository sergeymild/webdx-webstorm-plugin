package com.webdx.i18n

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiFile

/** Flattens a locale JSON file into dot-path keys and resolves keys back to PSI. */
internal object I18nKeys {

    /** All dot-paths whose value is a string leaf (`common.action.copy`). */
    fun collectKeys(jsonFile: PsiFile): Set<String> {
        val root = rootObject(jsonFile) ?: return emptySet()
        val out = LinkedHashSet<String>()
        collect(root, "", out)
        return out
    }

    // i18next CLDR plural categories. A base key `k` used with `{count}` resolves at runtime to
    // a suffixed variant (`k_one`/`k_other`/…, or `k_ordinal_one`/… for ordinals).
    private val PLURAL_SUFFIXES = listOf("zero", "one", "two", "few", "many", "other")

    /**
     * True when [key] is a valid translation key against [keys]: present literally, OR an
     * i18next plural/ordinal base whose suffixed variant exists (the locale JSON stores
     * `key_one`/`key_other` while code calls the base `key` with `{count}`).
     */
    fun isKnownKey(key: String, keys: Set<String>): Boolean {
        if (key in keys) return true
        for (cat in PLURAL_SUFFIXES) {
            if ("${key}_$cat" in keys || "${key}_ordinal_$cat" in keys) return true
        }
        return false
    }

    /** The `{{placeholder}}` names interpolated in a translation [value] (`{{x, fmt}}` -> `x`). */
    fun placeholdersOf(value: String): Set<String> =
        PLACEHOLDER.findAll(value)
            .map { it.groupValues[1].substringBefore(',').trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private val PLACEHOLDER = Regex("""\{\{([^}]*)\}\}""")

    /** The string value of [key], or null if it is not a valid string-leaf key. */
    fun valueOf(jsonFile: PsiFile, key: String): String? =
        (resolveProperty(jsonFile, key)?.value as? JsonStringLiteral)?.value

    /** The dot-path of a [property] in the locale JSON (inverse of [resolveProperty]). */
    fun pathOf(property: JsonProperty): String {
        val parts = ArrayList<String>()
        var prop: JsonProperty? = property
        while (prop != null) {
            parts.add(prop.name)
            prop = (prop.parent as? JsonObject)?.parent as? JsonProperty
        }
        return parts.asReversed().joinToString(".")
    }

    /** The JsonProperty for [key] (string leaf), or null if it is not a valid key. */
    fun resolveProperty(jsonFile: PsiFile, key: String): JsonProperty? {
        var obj = rootObject(jsonFile) ?: return null
        val parts = key.split('.')
        parts.forEachIndexed { i, part ->
            val prop = obj.findProperty(part) ?: return null
            if (i == parts.lastIndex) return prop.takeIf { it.value is JsonStringLiteral }
            obj = prop.value as? JsonObject ?: return null
        }
        return null
    }

    private fun rootObject(jsonFile: PsiFile): JsonObject? =
        (jsonFile as? JsonFile)?.topLevelValue as? JsonObject

    private fun collect(obj: JsonObject, prefix: String, out: MutableSet<String>) {
        for (prop in obj.propertyList) {
            val path = if (prefix.isEmpty()) prop.name else "$prefix.${prop.name}"
            when (val v = prop.value) {
                is JsonObject -> collect(v, path, out)
                is JsonStringLiteral -> out.add(path)
                else -> {} // numbers / arrays / booleans are not string keys
            }
        }
    }
}
