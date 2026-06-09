package com.intch.i18n

import com.intellij.json.psi.JsonProperty
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Flattening the locale JSON into a set of valid dot-path keys, and resolving a
 * key back to its JsonProperty for go-to-definition. Needs the bundled JSON PSI.
 */
class I18nKeysTest : BasePlatformTestCase() {

    private val sample = """
        {
          "common": {
            "action": { "copy": "Copy", "save": "Save" }
          },
          "page": { "title": "Title" },
          "empty": {}
        }
    """.trimIndent()

    fun testCollectsOnlyStringLeafPaths() {
        val json = myFixture.configureByText("en.json", sample)
        val keys = I18nKeys.collectKeys(json)
        assertEquals(
            setOf("common.action.copy", "common.action.save", "page.title"),
            keys,
        )
    }

    fun testResolvesKeyToItsProperty() {
        val json = myFixture.configureByText("en.json", sample)
        val prop = I18nKeys.resolveProperty(json, "common.action.copy")
        assertNotNull("expected to resolve common.action.copy", prop)
        assertEquals("copy", (prop as JsonProperty).name)
    }

    fun testUnknownKeyResolvesToNull() {
        val json = myFixture.configureByText("en.json", sample)
        assertNull(I18nKeys.resolveProperty(json, "common.action.nope"))
        // An intermediate object path is not a leaf key.
        assertNull(I18nKeys.resolveProperty(json, "common.action"))
    }

    fun testPathOfRoundTrips() {
        val json = myFixture.configureByText("en.json", sample)
        val leaf = I18nKeys.resolveProperty(json, "common.action.copy")!!
        assertEquals("common.action.copy", I18nKeys.pathOf(leaf))
        val branch = I18nKeys.pathOf(json.let {
            // the "common" property (a branch) should still produce its path
            (it as com.intellij.json.psi.JsonFile).topLevelValue
                .let { v -> (v as com.intellij.json.psi.JsonObject).findProperty("page")!! }
        })
        assertEquals("page", branch)
    }
}
