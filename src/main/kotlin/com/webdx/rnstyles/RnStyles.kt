package com.webdx.rnstyles

/**
 * Shared helpers for React Native `StyleSheet.create` styles, all on generic JS PSI
 * (no TypeScript language service). A "StyleSheet object" is a `StyleSheet.create({…})`
 * call; its top-level object-literal properties are the style keys.
 */
internal object RnStyles {

    /** Parse the inside of `import { … }` braces -> localName -> originalExportName. */
    fun parseNamedImports(braceContent: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in braceContent.split(',')) {
            val entry = raw.trim()
            if (entry.isEmpty()) continue
            val parts = entry.split(Regex("""\s+as\s+"""))
            val orig = parts[0].trim()
            val local = parts.getOrNull(1)?.trim() ?: orig
            if (orig.isNotEmpty() && local.isNotEmpty()) out[local] = orig
        }
        return out
    }

    /**
     * Parse the inside of destructuring `const { … } = x` braces -> localName -> sourceKeyName.
     * Handles shorthand, `key: local` rename, default values (`x = 5`), and skips `...rest`.
     * Known limitation: TypeScript typed-with-default patterns (`x: Type = v`) are not supported —
     * they do not occur in RN `StyleSheet` destructuring (keys are plain identifiers).
     */
    fun parseDestructuredEntries(braceContent: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in braceContent.split(',')) {
            val entry = raw.trim().substringBefore('=').trim() // drop default values
            if (entry.isEmpty() || entry.startsWith("...")) continue
            val parts = entry.split(':')
            val key = parts[0].trim()
            val local = parts.getOrNull(1)?.trim() ?: key
            if (key.isNotEmpty() && local.isNotEmpty()) out[local] = key
        }
        return out
    }
}
