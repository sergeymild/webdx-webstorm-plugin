package com.webdx.rnstyles

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

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

    // ---- detection ----

    fun isStyleSheetCreateCall(call: JSCallExpression): Boolean =
        call.methodExpression?.text?.trim() == "StyleSheet.create"

    /** The first-argument object literal of a `StyleSheet.create({...})` call, else null. */
    fun styleSheetObjectOf(call: JSCallExpression): JSObjectLiteralExpression? {
        if (!isStyleSheetCreateCall(call)) return null
        return call.arguments.firstOrNull() as? JSObjectLiteralExpression
    }

    /** Top-level property names of a StyleSheet object (computed/spread/unnamed skipped; duplicates deduped, first kept). */
    fun styleKeys(obj: JSObjectLiteralExpression): List<String> =
        obj.properties.mapNotNull { it.name }.distinct()

    /** The [JSProperty] declaring style key [name] in [obj], or null if absent. */
    fun keyProperty(obj: JSObjectLiteralExpression, name: String): JSProperty? =
        obj.properties.firstOrNull { it.name == name }

    /** The variable name a StyleSheet object is assigned to (`const <name> = StyleSheet.create(...)`). */
    fun bindingNameOf(obj: JSObjectLiteralExpression): String? =
        PsiTreeUtil.getParentOfType(obj, JSVariable::class.java)?.name

    /** Every `<name> = StyleSheet.create({...})` in [file]: binding name -> object literal. */
    fun fileStyleSheets(file: PsiFile): Map<String, JSObjectLiteralExpression> {
        val out = LinkedHashMap<String, JSObjectLiteralExpression>()
        for (call in PsiTreeUtil.collectElementsOfType(file, JSCallExpression::class.java)) {
            val obj = styleSheetObjectOf(call) ?: continue
            val name = bindingNameOf(obj) ?: continue
            out.putIfAbsent(name, obj)
        }
        return out
    }
}
