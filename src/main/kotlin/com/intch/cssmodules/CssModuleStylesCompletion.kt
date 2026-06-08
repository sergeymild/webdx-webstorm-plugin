package com.intch.cssmodules

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder

/**
 * Provides member completion for `styles.<caret>` directly from the imported CSS
 * module file, independent of the TypeScript service. When it kicks in, it also
 * suppresses the unrelated `any`-typed suggestions.
 */
class CssModuleStylesCompletion : CompletionContributor() {

    private val log = com.intellij.openapi.diagnostic.logger<CssModuleStylesCompletion>()

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        val position = parameters.position
        val dot = CssModules.prevMeaningfulLeaf(position)
        val qualifier = dot?.let { CssModules.prevMeaningfulLeaf(it) }
        val binding = qualifier?.text
        val moduleFile = if (binding != null && binding.isNotEmpty() && binding.first().isJavaIdentifierStart())
            CssModules.resolveModuleForBinding(file, binding) else null
        val classes = moduleFile?.let { CssModules.collectClassNames(it) } ?: emptyList()
        val msg = "fillCompletion file=${file.name} jsLike=${CssModules.isJsLikeFileName(file.name)} " +
            "pos='${runCatching { position.text }.getOrNull()}' dot='${dot?.text}' binding='$binding' " +
            "module=${moduleFile?.name} classes=$classes"
        runCatching { java.io.File("/tmp/css-scoped-completion.txt").writeText(msg) }
        log.warn("[CSS-SCOPED] $msg")

        if (!CssModules.isJsLikeFileName(file.name)) return
        if (dot?.text != ".") return
        if (moduleFile == null || classes.isEmpty()) return

        for (name in classes) {
            val element = LookupElementBuilder.create(name)
                .withTypeText(moduleFile.name, true)
            result.addElement(PrioritizedLookupElement.withPriority(element, 100.0))
        }
        // Run every other contributor (incl. the LSP one) through our consumer and
        // drop their results, so only the real CSS-module classes remain.
        result.runRemainingContributors(parameters) { /* intentionally dropped */ }
    }
}
