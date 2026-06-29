package com.webdx.cssmodules

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Regression coverage on three real-world Angular `*.component.scss` files (kept under
 * `src/test/resources/realworld/`). They exercise the bam resolver on production-grade
 * SCSS: string-`$var` interpolation selectors (`#{$x}`), `&`-BEM concatenation at depth,
 * comma-grouped BEM members, `#{$x}__suffix` inline interpolation, and value-position
 * interpolation (`#{palette.$midnight}`) that must NOT be mistaken for a class.
 *
 * Assertions are presence/absence of the interesting class names (not full-set equality),
 * so they stay robust as the (large, third-party-shaped) fixtures evolve.
 */
class BamSelectorsRealWorldTest : BasePlatformTestCase() {

    private fun bamKeysOf(resource: String): Set<String> {
        val text = javaClass.classLoader.getResourceAsStream("realworld/$resource")!!
            .readBytes().toString(Charsets.UTF_8)
        // Name it `.module.scss` so it is treated as a CSS module; the resolver itself is
        // file-name-agnostic, but this mirrors how the inspections would see it.
        val name = resource.removeSuffix(".component.scss") + ".module.scss"
        val scss = myFixture.addFileToProject("src/$name", text)
        return BamSelectors.bamClassDeclarations(scss).keys
    }

    fun testPricingCalculator() {
        val keys = bamKeysOf("pricing-calculator.component.scss")
        // literal-parent `.pricing-calculator { &__header {} }` and `.pricing-slider { … }`
        assertContainsElements(
            keys,
            "pricing-calculator__header",
            "pricing-calculator__license-badge--host",
            "pricing-calculator__license-badge--participant",
            "pricing-slider__range-input",
            "pricing-slider__mark",
        )
        // Pseudo-elements (`&::-webkit-slider-thumb`) and `::ng-deep p` declare no class.
        assertFalse(
            "pseudo-element selectors must not leak into class names: $keys",
            keys.any { it.contains(':') || it.contains("webkit") || it.contains("moz") || it.contains("ms-") },
        )
    }

    fun testCardsWithFilters() {
        val keys = bamKeysOf("cards-with-filters-v2.component.scss")
        // `$cards-with-filters: '.cards-with-filters'` used both literally and via `#{…}`.
        assertContainsElements(
            keys,
            "cards-with-filters",
            "cards-with-filters__container",
            // headline: comma-grouped BEM members beyond the first (`&__cards-with-btn, &__emptyState`)
            "cards-with-filters__cards-with-btn",
            "cards-with-filters__emptyState",
            // comma group of three (`&__filters-mobile, &__heading, &__apply`)
            "cards-with-filters__filters-mobile",
            // deep `#{$x} { &__mobile-popup { &--opened {} } }` through an @include container
            "cards-with-filters__mobile-popup",
            "cards-with-filters__mobile-popup--opened",
            // `&-search` partial-word suffix on `&__filter-title`
            "cards-with-filters__filter-title-search",
        )
    }

    fun testAbstractMarketoForm() {
        val keys = bamKeysOf("abstract-marketo-form.component.scss")
        assertContainsElements(
            keys,
            "website-marketo-form",
            "website-marketo-form__title",
            "website-marketo-form__title--center",
            "website-marketo-form__subtitle",
            "website-marketo-form--footer",
            // a SECOND string var (`$shortener-section`) interpolated deep inside ::ng-deep
            "shortener-section",
            "shortener-section__title",
        )
        // `#{palette.$midnight}` / `#{$marketo-form-input-border-width}` appear in VALUE
        // position — they must never be collected as class names.
        assertFalse(
            "value-position interpolation must not become a class: $keys",
            keys.any { it.contains("midnight") || it.contains("palette") || it.contains("border-width") },
        )
    }
}
