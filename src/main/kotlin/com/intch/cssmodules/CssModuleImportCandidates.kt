package com.intch.cssmodules

import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration
import com.intellij.lang.ecmascript6.psi.impl.ES6ImportPsiUtil
import com.intellij.lang.javascript.modules.JSImportCandidateDescriptor
import com.intellij.lang.javascript.modules.JSImportPlaceInfo
import com.intellij.lang.javascript.modules.imports.JSImportCandidate
import com.intellij.lang.javascript.modules.imports.JSModuleDescriptor
import com.intellij.lang.javascript.modules.imports.filter.JSImportCandidatesFilter
import com.intellij.lang.javascript.modules.imports.providers.JSCandidatesProcessor
import com.intellij.lang.javascript.modules.imports.providers.JSImportCandidatesProvider
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiManager

private val LOG = logger<CssModuleImportCandidatesFactory>()

private fun isCssModuleFileName(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith(".module.css") ||
        n.endsWith(".module.scss") ||
        n.endsWith(".module.sass") ||
        n.endsWith(".module.less")
}

/**
 * When an identifier (e.g. `styles`) is unresolved, offer to import a CSS module
 * (`*.module.scss|css|less`) that sits right next to the current file —
 * `import styles from './ThisComponent.module.scss'` — instead of only the
 * unrelated `styles` exports from node_modules.
 */
class CssModuleImportCandidatesFactory : JSImportCandidatesProvider.CandidatesFactory {
    override fun createProvider(placeInfo: JSImportPlaceInfo): JSImportCandidatesProvider {
        runCatching {
            java.io.File("/tmp/css-scoped-import-create.txt")
                .writeText("createProvider called, file=${placeInfo.file?.name}")
        }
        LOG.warn("[CSS-SCOPED] importCandidates createProvider called, file=${placeInfo.file?.name}")
        return CssModuleImportProvider(placeInfo)
    }
}

private class CssModuleImportProvider(
    private val place: JSImportPlaceInfo,
) : JSImportCandidatesProvider {

    override fun processCandidates(name: String, processor: JSCandidatesProcessor) {
        runCatching {
            java.io.File("/tmp/css-scoped-import-process.txt")
                .writeText("processCandidates name='$name' file=${place.file?.name}")
        }
        LOG.warn("[CSS-SCOPED] processCandidates name='$name' file=${place.file?.name}")
        val currentFile = place.file ?: return
        val dir = currentFile.parent ?: return
        val psiManager = PsiManager.getInstance(place.project)

        // Sibling modules first, then prefer the one named after the current file.
        val siblings = dir.children
            .filter { it != currentFile && !it.isDirectory && isCssModuleFileName(it.name) }
            .sortedByDescending { matchesCurrentFile(it.name, currentFile.name) }

        for (vf in siblings) {
            val psiFile = psiManager.findFile(vf) ?: continue
            // Build a DEFAULT import: `import <name> from './X.module.scss'`.
            // Siblings live in the same directory, so the specifier is always "./<file>".
            val specifier = "./${vf.name}"
            val moduleDescriptor = JSModuleDescriptor.SimpleModuleDescriptor(psiFile, specifier)
            val candidate = JSImportCandidateDescriptor(
                moduleDescriptor,
                name,                                                 // importedName = local binding
                name,                                                 // exportedName (ignored for default form)
                ES6ImportExportDeclaration.ImportExportPrefixKind.IMPORT,
                ES6ImportPsiUtil.ImportExportType.DEFAULT,
            )
            processor.processCandidate(candidate)
            LOG.warn("[CSS-SCOPED] import candidate: 'import $name from $specifier'")
        }
    }

    /** `ProfileSettingsMobile.tsx` <-> `ProfileSettingsMobile.module.scss`. */
    private fun matchesCurrentFile(moduleName: String, currentName: String): Boolean {
        val base = currentName.substringBeforeLast('.')
        return moduleName.startsWith("$base.module", ignoreCase = true)
    }
}

private fun isCssModuleCandidate(c: JSImportCandidate): Boolean {
    c.elementFile?.let { if (isCssModuleFileName(it.name)) return true }
    val moduleName = runCatching { c.descriptor?.moduleName }.getOrNull()
    return moduleName != null && isCssModuleFileName(moduleName)
}

/**
 * When a CSS-module import is offered for the unresolved name, drop the unrelated
 * candidates (e.g. `import { styles } from 'next/dist/...'`) so only the local
 * module import remains.
 */
class CssModuleImportFilterFactory : JSImportCandidatesFilter.FilterFactory {
    override fun createFilter(candidates: List<JSImportCandidate>): JSImportCandidatesFilter {
        val hasCssModule = candidates.any { isCssModuleCandidate(it) }
        return object : JSImportCandidatesFilter {
            override fun accept(candidate: JSImportCandidate): Boolean {
                return !hasCssModule || isCssModuleCandidate(candidate)
            }
        }
    }
}
