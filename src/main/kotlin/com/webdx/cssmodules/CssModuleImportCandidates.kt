package com.webdx.cssmodules

import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration
import com.intellij.lang.ecmascript6.psi.impl.ES6ImportPsiUtil
import com.intellij.lang.javascript.modules.JSImportCandidateDescriptor
import com.intellij.lang.javascript.modules.JSImportPlaceInfo
import com.intellij.lang.javascript.modules.imports.JSImportCandidate
import com.intellij.lang.javascript.modules.imports.JSModuleDescriptor
import com.intellij.lang.javascript.modules.imports.filter.JSImportCandidatesFilter
import com.intellij.lang.javascript.modules.imports.providers.JSCandidatesProcessor
import com.intellij.lang.javascript.modules.imports.providers.JSImportCandidatesProvider
import com.intellij.psi.PsiManager

private fun isCssModuleFileName(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith(".module.css") ||
        n.endsWith(".module.scss") ||
        n.endsWith(".module.sass") ||
        n.endsWith(".module.less")
}

/** `ProfileSettingsMobile.tsx` <-> `ProfileSettingsMobile.module.scss`. */
private fun matchesCurrentFile(moduleName: String, currentName: String): Boolean {
    val base = currentName.substringBeforeLast('.')
    return moduleName.startsWith("$base.module", ignoreCase = true)
}

private val MODULE_SUFFIX = Regex("""\.module\.(css|scss|sass|less)$""", RegexOption.IGNORE_CASE)

/**
 * Local binding to use when auto-importing [moduleFileName] into [currentFileName].
 * If the module is the one named after the current component, keep what the user
 * typed ([typedName]); otherwise derive a camelCase name from the module file
 * (`Sidebar.module.scss` -> `sidebar`, `user-profile.module.scss` -> `userProfile`).
 * Falls back to [typedName] when no valid identifier can be formed.
 */
internal fun importBindingFor(typedName: String, moduleFileName: String, currentFileName: String): String {
    if (matchesCurrentFile(moduleFileName, currentFileName)) return typedName
    val base = moduleFileName.replaceFirst(MODULE_SUFFIX, "")
    return camelCaseIdentifier(base) ?: typedName
}

/** The conventional default binding for a CSS module (`import styles from './X.module.scss'`). */
private const val CONVENTIONAL_STYLES_BINDING = "styles"

/**
 * Whether the sibling CSS module [moduleFileName] should be offered as an auto-import
 * for the unresolved identifier [typedName] in [currentFileName].
 *
 * The unresolved-reference quick fix fires for EVERY unknown name, so without this
 * guard any identifier (e.g. an un-imported `picksAnalytics`) would get a bogus
 * `import picksAnalytics from './X.module.scss'` candidate — and [CssModuleImportFilterFactory]
 * would then suppress the real candidates (the named import the user actually wants).
 * Only offer when the typed name is a plausible style binding: the conventional `styles`,
 * or the module-derived camelCase name (`Sidebar.module.scss` -> `sidebar`).
 */
internal fun shouldOfferModuleImport(typedName: String, moduleFileName: String, currentFileName: String): Boolean {
    if (typedName == CONVENTIONAL_STYLES_BINDING) return true
    return typedName == importBindingFor(CONVENTIONAL_STYLES_BINDING, moduleFileName, currentFileName)
}

/** `UserProfile` / `user-profile` / `user_profile` -> `userProfile`; null if not a valid identifier. */
private fun camelCaseIdentifier(base: String): String? {
    val words = base.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return null
    val result = buildString {
        words.forEachIndexed { i, w ->
            append(if (i == 0) w.replaceFirstChar { it.lowercaseChar() } else w.replaceFirstChar { it.uppercaseChar() })
        }
    }
    return result.takeIf { it.first().isJavaIdentifierStart() }
}

/**
 * When an identifier (e.g. `styles`) is unresolved, offer to import a CSS module
 * (`*.module.scss|css|less`) that sits right next to the current file —
 * `import styles from './ThisComponent.module.scss'` — instead of only the
 * unrelated `styles` exports from node_modules.
 */
class CssModuleImportCandidatesFactory : JSImportCandidatesProvider.CandidatesFactory {
    override fun createProvider(placeInfo: JSImportPlaceInfo): JSImportCandidatesProvider {
        return CssModuleImportProvider(placeInfo)
    }
}

private class CssModuleImportProvider(
    private val place: JSImportPlaceInfo,
) : JSImportCandidatesProvider {

    override fun processCandidates(name: String, processor: JSCandidatesProcessor) {
        val currentFile = place.file ?: return
        val dir = currentFile.parent ?: return
        val psiManager = PsiManager.getInstance(place.project)

        // Sibling modules first, then prefer the one named after the current file.
        val siblings = dir.children
            .filter { it != currentFile && !it.isDirectory && isCssModuleFileName(it.name) }
            .sortedByDescending { matchesCurrentFile(it.name, currentFile.name) }

        for (vf in siblings) {
            // Only offer a module when the typed name is a plausible style binding, so an
            // unrelated unresolved identifier (e.g. `picksAnalytics`) is never offered a
            // CSS-module default import — and the companion filter never suppresses the
            // real candidate the user actually wants.
            if (!shouldOfferModuleImport(name, vf.name, currentFile.name)) continue
            val psiFile = psiManager.findFile(vf) ?: continue
            // Build a DEFAULT import under the typed name: `import <name> from './X.module.scss'`.
            // This path (the unresolved-reference quick fix) only adds the import — it does not
            // rename the reference — so it must keep the typed name or it would leave broken code.
            // Renaming to a module-derived binding (`Sidebar.module.scss` -> `sidebar`) is handled
            // by the completion entry in CssModuleStylesCompletion, which also rewrites the usage.
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
        }
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
