package com.webdx.barrels

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BarrelExportIntentionTest : BasePlatformTestCase() {

    private val family = "Export through barrel modules"
    private fun tsconfig() =
        myFixture.addFileToProject("tsconfig.json", """{ "compilerOptions": { "paths": { "@/*": ["./*"] } } }""")
    /**
     * Adjustment 3: `VfsUtilCore.loadText(vf)` reads stale VFS bytes after a document-buffer edit.
     * The barrel intention writes via `FileDocumentManager.getDocument(indexFile).insertString(…)`
     * but does not flush to VFS; the document is the source of truth immediately after apply.
     * Reading via `FileDocumentManager.getDocument(vf)?.text` returns the live document content.
     */
    private fun textOf(path: String): String {
        val vf = myFixture.findFileInTempDir(path)
        return com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)?.text
            ?: com.intellij.openapi.vfs.VfsUtilCore.loadText(vf)
    }

    /**
     * Adjustment 1: `configureByText(path, text)` rejects paths that contain `/` in this SDK
     * (throws "Invalid file name"). Use `addFileToProject` + `configureFromExistingVirtualFile`
     * instead, and manually restore the caret position encoded as `<caret>` in the text.
     *
     * Adjustment 2: `filterAvailableIntentions(hint)` matches against `getText()` (the dynamic
     * display text, e.g. "Export through barrels up to components"), not `getFamilyName()`.
     * Use `myFixture.availableIntentions.filter { it.familyName == family }` to match by
     * family name instead. Assertions on behavior are unchanged.
     */
    private fun configureByPathAndText(path: String, text: String) {
        val caretOffset = text.indexOf("<caret>")
        val cleanText = text.replace("<caret>", "")
        val file = myFixture.addFileToProject(path, cleanText)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        if (caretOffset >= 0) {
            myFixture.editor.caretModel.moveToOffset(caretOffset)
        }
    }

    private fun filterByFamily() =
        myFixture.availableIntentions.filter { it.familyName == family }

    fun testOfferedAndAppliesIntchShape() {
        tsconfig()
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './Other'\n")
        myFixture.addFileToProject("components/Button/index.ts", "export * from './placeholder'\n")
        configureByPathAndText("components/Button/Button.tsx", "export const But<caret>ton = () => null\n")
        val intention = filterByFamily().first()
        myFixture.launchAction(intention)
        assertTrue(textOf("components/Button/index.ts").contains("export * from './Button'"))
        assertTrue(textOf("components/index.ts").contains("export * from './Button'"))
    }

    fun testAppliesDefaultExportConversion() {
        tsconfig()
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("src/containers/index.ts", "export * from './Basic'\n")
        myFixture.addFileToProject("src/containers/Touchpoint/index.ts", "export { default } from './Touchpoint'\n")
        configureByPathAndText(
            "src/containers/Touchpoint/Touchpoint.tsx",
            "export default function Touch<caret>point() {}\n",
        )
        myFixture.launchAction(filterByFamily().first())
        assertTrue(
            textOf("src/containers/index.ts").contains("export { default as Touchpoint } from './Touchpoint'"),
        )
    }

    fun testNotOfferedInIndexFile() {
        tsconfig()
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        configureByPathAndText("components/index.ts", "export const Hel<caret>per = 1\n")
        assertEmpty(filterByFamily())
    }

    fun testNotOfferedWithoutBarrelAncestor() {
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        configureByPathAndText("loose/Button.tsx", "export const But<caret>ton = 1\n")
        assertEmpty(filterByFamily())
    }

    fun testNotOfferedWhenAlreadyWired() {
        tsconfig()
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './Button'\n")
        myFixture.addFileToProject("components/Button/index.ts", "export * from './Button'\n")
        configureByPathAndText("components/Button/Button.tsx", "export const But<caret>ton = 1\n")
        assertEmpty(filterByFamily())
    }

    fun testNotOfferedForNonExported() {
        tsconfig()
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './x'\n")
        configureByPathAndText("components/Button/Button.tsx", "const Pri<caret>vate = 1\n")
        assertEmpty(filterByFamily())
    }

    fun testGeneratePreviewDoesNotThrowAndDescribesEdits() {
        tsconfig()
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './Other'\n")
        myFixture.addFileToProject("components/Button/index.ts", "export * from './placeholder'\n")
        configureByPathAndText("components/Button/Button.tsx", "export const But<caret>ton = () => null\n")
        val intention = filterByFamily().first()
        // generatePreview must not throw (no write-in-read-action) and must mention the planned edit.
        // getIntentionPreviewText returns null for Html previews (only for diff previews); use
        // getPreviewContent which returns the Html string for Html previews.
        val preview = com.intellij.openapi.application.ReadAction.compute<String, Exception> {
            com.intellij.codeInsight.intention.impl.preview.IntentionPreviewPopupUpdateProcessor
                .getPreviewContent(project, intention, myFixture.file, myFixture.editor)
        }
        assertNotNull("Preview must not be null — generatePreview should not crash", preview)
        assertFalse(
            "Preview should not be empty — generatePreview returned EMPTY",
            preview!!.isEmpty(),
        )
        assertTrue(
            "Preview should mention 'export' (a planned re-export line)",
            preview.contains("export", ignoreCase = true),
        )
        assertTrue(
            "Preview should mention 'Button' (the symbol / file being wired)",
            preview.contains("Button"),
        )
    }

    fun testReInvocationProducesNoDuplicateLine() {
        tsconfig()
        myFixture.addFileToProject("package.json", """{ "name": "web" }""")
        myFixture.addFileToProject("components/index.ts", "export * from './Other'\n")
        myFixture.addFileToProject("components/Button/index.ts", "export * from './placeholder'\n")
        configureByPathAndText("components/Button/Button.tsx", "export const But<caret>ton = () => null\n")

        // First invocation — wires both barrels
        val intention = filterByFamily().first()
        myFixture.launchAction(intention)

        // After first apply the intention must NOT be offered (already fully wired)
        assertEmpty(filterByFamily())

        // The re-export line must appear exactly once in the leaf barrel
        assertEquals(
            1,
            Regex(Regex.escape("export * from './Button'"))
                .findAll(textOf("components/Button/index.ts")).count(),
        )
    }
}
