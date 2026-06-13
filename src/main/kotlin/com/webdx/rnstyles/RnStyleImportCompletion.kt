package com.webdx.rnstyles

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.webdx.cssmodules.CssModules

/**
 * Auto-import of a sibling React Native StyleSheet module. Typing a bare identifier
 * (e.g. `styles`) offers an entry per exported `StyleSheet.create` binding found in a
 * sibling file of the same directory; selecting it inserts
 * `import { <binding> } from './<sibling>'` by direct document editing (no TS service).
 * Only offers when a sibling actually exports it and the binding isn't already available
 * in the current file (local const or existing import). The sibling scan covers every JS/TS file
 * in the directory (no `*.test`/`*.stories` filtering) — a sibling is only offered if it actually
 * exports a `StyleSheet.create` binding, so unrelated files are naturally excluded.
 */
class RnStyleImportCompletion : CompletionContributor() {

    // Don't offer an auto-import where an expression can't go.
    private val NON_EXPRESSION_PREV = setOf(
        ".", "import", "from", "function", "class", "const", "let", "var",
        "interface", "type", "enum", "namespace",
    )

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!CssModules.isJsLikeFileName(file.name)) return

        val prev = CssModules.prevMeaningfulLeaf(parameters.position)
        if (prev?.text in NON_EXPRESSION_PREV) return

        val dir = file.virtualFile?.parent ?: return
        val currentName = file.name
        val already = RnStyles.bindingsInFile(file).keys
        val psiManager = PsiManager.getInstance(file.project)

        for (vf in dir.children) {
            if (vf.isDirectory || vf.name == currentName || !CssModules.isJsLikeFileName(vf.name)) continue
            val sibling = psiManager.findFile(vf) ?: continue
            val exported = RnStyles.exportedStyleSheetBindings(sibling)
            if (exported.isEmpty()) continue
            val specifier = "./${vf.nameWithoutExtension}"
            for (binding in exported.keys) {
                if (binding in already) continue // already a local const or imported here
                val element = LookupElementBuilder.create(binding)
                    .withTypeText(vf.name, true)
                    .withInsertHandler { ctx, _ -> insertNamedImport(ctx, binding, specifier) }
                result.addElement(PrioritizedLookupElement.withPriority(element, 50.0))
            }
        }
    }

    /** Insert `import { <binding> } from '<specifier>';` at the top, unless it's already imported. */
    private fun insertNamedImport(ctx: InsertionContext, binding: String, specifier: String) {
        val doc = ctx.document
        val existing = Regex(
            """import\s*\{[^}]*\b${Regex.escape(binding)}\b[^}]*\}\s*from\s*['"]${Regex.escape(specifier)}['"]""",
        )
        if (existing.containsMatchIn(doc.text)) return
        doc.insertString(0, "import { $binding } from '$specifier';\n")
        PsiDocumentManager.getInstance(ctx.project).commitDocument(doc)
    }
}
