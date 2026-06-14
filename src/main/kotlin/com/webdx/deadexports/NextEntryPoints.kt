package com.webdx.deadexports

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * Recognizes Next.js convention-based entry points (files the framework loads by file path,
 * not by import). Re-exports in such files — and anything reachable only through them — must
 * not be reported as dead, because no explicit importer will ever exist.
 */
object NextEntryPoints {
    // App Router special files (Pages Router: ALL files under pages/ are entry points).
    private val APP_RESERVED = setOf(
        "page", "layout", "route", "loading", "error", "template",
        "default", "not-found", "global-error",
    )
    private val NEXT_CONFIG = listOf(
        "next.config.js", "next.config.ts", "next.config.mjs", "next.config.cjs",
    )

    fun isEntryPoint(file: PsiFile): Boolean {
        val vf = file.originalFile.virtualFile ?: return false
        var dir = vf.parent
        while (dir != null) {
            when (dir.name) {
                "pages" -> if (hasNextConfigAbove(dir)) return true          // any file under pages/ is a route/api/special
                "app" -> if (hasNextConfigAbove(dir) && vf.nameWithoutExtension in APP_RESERVED) return true
            }
            dir = dir.parent
        }
        return false
    }

    /**
     * next.config.* must sit beside the router dir, or — when the router dir lives under a `src/`
     * dir — beside that `src/`'s parent (the conventional project root). We check both the router
     * dir's immediate parent and, if that parent is `src`, its grandparent.
     */
    private fun hasNextConfigAbove(routerDir: VirtualFile): Boolean {
        val parent = routerDir.parent ?: return false
        val candidates = buildList {
            add(parent)
            if (parent.name == "src") parent.parent?.let { add(it) }
        }
        return candidates.any { root -> NEXT_CONFIG.any { root.findChild(it) != null } }
    }
}
