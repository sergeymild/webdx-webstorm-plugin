package com.webdx.deadexports

import com.intellij.lang.ecmascript6.psi.ES6ExportSpecifier
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Find Usages (and thus the Cmd+Click Show Usages popup) on a re-export specifier lists ONLY the
 * direct single-hop sites drawing the name from this barrel, routed via
 * [BarrelReExportFindUsagesHandlerFactory].
 */
class BarrelReExportFindUsagesTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("comp/UniversalCard.tsx", "export const UniversalCard = () => null\n")
        myFixture.addFileToProject("comp/index.ts", "export { UniversalCard } from './UniversalCard'\n")
        myFixture.addFileToProject("parent/index.ts", "export { UniversalCard } from '../comp'\n")
        myFixture.addFileToProject("Direct.tsx", "import { UniversalCard } from './comp'\nexport const D = () => UniversalCard\n")
        myFixture.addFileToProject("Chain.tsx", "import { UniversalCard } from './parent'\nexport const C = () => UniversalCard\n")
        myFixture.addFileToProject("Deep.tsx", "import { UniversalCard } from './comp/UniversalCard'\nexport const E = () => UniversalCard\n")
    }

    fun testFindUsagesScopedToDirectSites() {
        val vf = myFixture.findFileInTempDir("comp/index.ts")
        val barrel = PsiManager.getInstance(project).findFile(vf)!!
        val spec = PsiTreeUtil.findChildrenOfType(barrel, ES6ExportSpecifier::class.java)
            .first { it.referenceName == "UniversalCard" }

        val usages = myFixture.findUsages(spec)
        val files = usages.mapNotNull { it.element?.containingFile?.name }.toSet()

        assertTrue("direct importer Direct.tsx should appear, got: $files", "Direct.tsx" in files)
        assertTrue("direct re-exporter parent/index.ts should appear, got: $files",
            usages.any { it.element?.containingFile?.virtualFile?.path?.contains("/parent/") == true })
        assertFalse("chain-only Chain.tsx must NOT appear, got: $files", "Chain.tsx" in files)
        assertFalse("deep importer Deep.tsx must NOT appear, got: $files", "Deep.tsx" in files)
    }
}
