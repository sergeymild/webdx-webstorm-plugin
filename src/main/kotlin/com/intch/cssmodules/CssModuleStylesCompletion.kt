package com.intch.cssmodules

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Two CSS-module completions, both independent of the TypeScript service:
 *  - after `styles.` -> the real class names from the imported module (and the
 *    `any`-typed LSP garbage is suppressed);
 *  - on a bare identifier -> auto-import of a sibling CSS module. Selecting the
 *    entry renames what you typed to the chosen binding (the lookup string does
 *    that) and inserts `import <binding> from './X.module.scss'`. The binding is
 *    the module's name (`Sidebar.module.scss` -> `sidebar`), except the
 *    component's own module keeps `styles` (see [importBindingFor]).
 */
class CssModuleStylesCompletion : CompletionContributor() {

    private val log = com.intellij.openapi.diagnostic.logger<CssModuleStylesCompletion>()

    // Cheap context guard: don't offer an auto-import where an expression can't go.
    private val NON_EXPRESSION_PREV = setOf(
        ".", "import", "from", "function", "class", "const", "let", "var",
        "interface", "type", "enum", "namespace",
    )

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!CssModules.isJsLikeFileName(file.name)) return

        val position = parameters.position
        val dot = CssModules.prevMeaningfulLeaf(position)

        if (dot?.text == ".") {
            fillMemberCompletion(file, position, parameters, result)
        } else {
            fillModuleImportCompletion(file, dot?.text, result)
        }
    }

    /** `styles.<caret>` -> class names from the imported module; drop everything else. */
    private fun fillMemberCompletion(
        file: PsiFile,
        position: com.intellij.psi.PsiElement,
        parameters: CompletionParameters,
        result: CompletionResultSet,
    ) {
        val dot = CssModules.prevMeaningfulLeaf(position)
        val qualifier = dot?.let { CssModules.prevMeaningfulLeaf(it) }
        val binding = qualifier?.text
        val moduleFile = if (binding != null && binding.isNotEmpty() && binding.first().isJavaIdentifierStart())
            CssModules.resolveModuleForBinding(file, binding) else null
        val classes = moduleFile?.let { CssModules.collectAllClassNames(it) } ?: emptyList()
        if (moduleFile == null || classes.isEmpty()) return

        for (name in classes) {
            val element = LookupElementBuilder.create(name).withTypeText(moduleFile.name, true)
            result.addElement(PrioritizedLookupElement.withPriority(element, 100.0))
        }
        // Run every other contributor (incl. the LSP one) through our consumer and
        // drop their results, so only the real CSS-module classes remain.
        result.runRemainingContributors(parameters) { /* intentionally dropped */ }
    }

    /** Bare identifier -> offer to auto-import each sibling CSS module under a sensible binding. */
    private fun fillModuleImportCompletion(file: PsiFile, prevLeafText: String?, result: CompletionResultSet) {
        if (prevLeafText in NON_EXPRESSION_PREV) return
        val dir = file.virtualFile?.parent ?: return
        val currentName = file.name
        val siblings = dir.children
            .filter { !it.isDirectory && it.name != currentName && CssModules.isModuleFileName(it.name) }

        for (vf in siblings) {
            val binding = importBindingFor("styles", vf.name, currentName)
            val specifier = "./${vf.name}"
            val element = LookupElementBuilder.create(binding)
                .withTypeText(vf.name, true)
                // Discoverable when the user types the conventional `styles`, even
                // though the entry inserts the module-named binding.
                .withLookupString("styles")
                .withInsertHandler { ctx, _ -> insertModuleImport(ctx, binding, specifier) }
            result.addElement(PrioritizedLookupElement.withPriority(element, 50.0))
        }
    }

    /** Insert `import <binding> from '<specifier>';` at the top, unless it already exists. */
    private fun insertModuleImport(ctx: InsertionContext, binding: String, specifier: String) {
        val doc = ctx.document
        val already = Regex(
            """import\s+${Regex.escape(binding)}\s+from\s+['"]${Regex.escape(specifier)}['"]""",
        )
        if (already.containsMatchIn(doc.text)) return
        doc.insertString(0, "import $binding from '$specifier';\n")
        PsiDocumentManager.getInstance(ctx.project).commitDocument(doc)
    }
}
