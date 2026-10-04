package com.mrpoodles.app

import org.junit.Assert.*
import org.junit.Test

/** Synthetic parser regressions; no live source or production nutrition claim. */
class Phase7NutritionTest {
    private fun source(title: String = "Banana nutrition", heading: String = "# Banana nutrition",
        calories: String = "Calories: 100 kcal", kind: String = "nutrition", basis: String = "Serving size: 1 medium banana",
        extra: String = "", protein: String = "Protein: 1 g") = RetrievedSource(
        "source", "https://tools.myfooddata.com/fixture/banana", title, "2026-10-04T10:00:00Z",
        excerpt = "$heading\nSynthetic parser fixture; these are not real nutrition values.\n$basis\n$calories\n$protein\nCarbohydrate: 25 g\nTotal fat: 0.3 g\n$extra",
        kind = kind)

    private fun estimate(source: RetrievedSource, food: String = "banana", amount: Double = 1.0, unit: String = "piece") =
        FoodLogNutrition.estimate(EatenFood(food, Portion(amount, unit, "medium $food", grams = amount.takeIf { unit == "g" })),
            RetrievalSnapshot("snapshot", "request", listOf(source)))

    private fun assertUnknown(source: RetrievedSource, food: String = "banana") {
        val value = estimate(source, food)
        assertNull("${source.title}\n${source.excerpt}", value.kcal)
        assertEquals(EstimateStatus.UNKNOWN, value.status)
    }

    @Test fun fruitDoesNotMatchPreparedFoodsOrAdditionalFoodTermsInTitle() {
        for (title in listOf("Banana bread nutrition", "Banana smoothie nutrition", "Nutrition facts for fried bananas",
            "Banana and apple nutrition", "Banana cake calories", "Chocolate banana nutrition", "Banana nutrition bread")) {
            assertUnknown(source(title = title))
        }
        assertUnknown(source(title = "Banana nutrition"), "banana bread")
        assertUnknown(source(title = "Raw banana nutrition"))
        assertUnknown(source(title = "Cooked banana nutrition"), "raw banana")
    }

    @Test fun exactAnchoredNutritionWrappersAndPluralFormsRemainSupported() {
        for (title in listOf("Bananas", "Bananas nutrition", "Nutrition facts for bananas", "Banana calories", "Calories in banana")) {
            assertEquals(title, 100.0, estimate(source(title = title)).kcal!!, 0.001)
        }
        assertEquals(100.0, estimate(source(title = "Banana nutrition"), "bananas").kcal!!, 0.001)
        assertEquals(100.0, estimate(source(title = "Raw bananas nutrition", heading = "# Raw banana nutrition"), "raw banana").kcal!!, 0.001)
    }

    @Test fun onionSynonymDoesNotEraseTheRestOfTheFoodIdentity() {
        val page = source(title = "Onion kachoris nutrition", heading = "# Pyaaz kachori nutrition", basis = "Serving size: 1 medium kachori")
        assertEquals(100.0, estimate(page, "pyaz kachori").kcal!!, 0.001)
        assertUnknown(page.copy(title = "Onion kachori chaat nutrition"), "pyaaz kachori")
    }

    @Test fun pluralLoanwordFoodsMatchWithoutAcceptingAdditionalDishTerms() {
        for ((singular, plural) in listOf("kachori" to "kachoris", "roti" to "rotis", "chapati" to "chapatis", "puri" to "puris", "idli" to "idlis")) {
            val page = source(title = "$singular nutrition", heading = "# $singular nutrition", basis = "Serving size: 1 medium $singular")
            assertEquals(plural, 100.0, estimate(page, plural).kcal!!, 0.001)
            assertUnknown(page.copy(title = "$plural chaat nutrition"), plural)
        }
    }

    @Test fun proseEndingInNutritionValuesIsNotAHeadingButExplicitConflictingHeadingsStillReject() {
        val page = source(extra = "Synthetic background; this sentence discusses nutrition values.")
        assertEquals(100.0, estimate(page).kcal!!, 0.001)
        assertUnknown(page.copy(excerpt = page.excerpt + "\nBanana bread nutrition values"))
        assertUnknown(page.copy(excerpt = page.excerpt + "\n# Banana bread; nutrition values"))
        assertUnknown(page.copy(excerpt = page.excerpt + "\nFood: Banana bread; nutrition values"))
    }

    @Test fun correctTitleCannotOverrideDifferentBodyFoodOrRecipeHeaders() {
        for (header in listOf("# Banana bread nutrition", "## Banana smoothie", "Banana bread", "Food: Banana bread",
            "Recipe: Banana cake", "Product name: Banana chips", "Nutrition facts for banana bread",
            "Banana bread\n===========", "<h1>Banana bread</h1>")) {
            assertUnknown(source(heading = header))
        }
        assertUnknown(source(heading = "# Banana nutrition\n## Banana bread"))
        assertUnknown(source(extra = "## Banana bread nutrition"))
    }

    @Test fun servingFoodSuffixCannotChangeFruitIntoBreadUnderCorrectHeadings() {
        assertUnknown(source(basis = "Serving size: 1 medium banana bread"))
        assertUnknown(source(basis = "Serving size: 1 medium banana smoothie"))
        assertEquals(100.0, estimate(source(basis = "Serving size: 1 medium banana (100 g)")).kcal!!, 0.001)
    }

    @Test fun onlyNutritionAndBackendReviewedManufacturerKindsAreEligible() {
        for (kind in listOf("recipe", "legacy", "article", "workout", "youtube_metadata", "")) assertUnknown(source(kind = kind))
        assertEquals(100.0, estimate(source(kind = "nutrition")).kcal!!, 0.001)
        assertEquals(100.0, estimate(source(kind = "manufacturer")).kcal!!, 0.001)
        assertUnknown(source(kind = "manufacturer", title = "Banana bread nutrition"))
    }

    @Test fun groupedCaloriesAreWholeValuesNeverSuffixesOrZero() {
        for ((spelling, expected) in listOf("1,200" to 1200.0, "1,000" to 1000.0, "12,345.5" to 12345.5, "1,234,567" to 1234567.0)) {
            for (row in listOf("Calories: $spelling kcal", "$spelling kcal", "Calories: $spelling", "Energy: $spelling kilocalories")) {
                val result = estimate(source(calories = row))
                assertEquals(row, expected, result.kcal!!, 0.001)
                assertTrue(result.evidence.single().excerpt.contains(row))
            }
        }
    }

    @Test fun malformedNumbersNeverBecomeAValidSuffix() {
        for (spelling in listOf("1,20", "1,00", "1,,200", "12,34,567", "1.200,5", "1..200", "1,200.0.5",
            "1e3", "1e+3", "-200", "+200", "−200", ".200", "1/200", "1:200", "1-200", "1_200", "1'200",
            "1 200", "1\u00a0200", "1\u202f200", "200.", "200,", "001,200")) {
            for (row in listOf("Calories: $spelling kcal", "$spelling kcal", "Calories: $spelling")) assertUnknown(source(calories = row))
        }
        assertUnknown(source(calories = "Calories: < 200 kcal"))
        assertUnknown(source(calories = "Calories: 1,20 kcal\nCalories: 200 kcal"))
    }

    @Test fun malformedMacrosStayUnknownWithoutCorruptingValidCalories() {
        for (spelling in listOf("1,20", "1.2.3", "1 000", "-2", "1e3")) {
            val value = estimate(source(protein = "Protein: $spelling g"))
            assertEquals(100.0, value.kcal!!, 0.001)
            assertNull(spelling, value.protein)
        }
        assertEquals(1200.0, estimate(source(protein = "Protein: 1,200 g")).protein!!, 0.001)
    }

    @Test fun groupedServingBasisUsesFullAmountAndMalformedBasisIsUnknown() {
        val page = source(basis = "Per 1,000 g", calories = "Calories: 1,200 kcal")
        assertEquals(120.0, estimate(page, amount = 100.0, unit = "g").kcal!!, 0.001)
        for (basis in listOf("Per 1,00 g", "Per 1,,000 g", "Per 1 000 g", "Per 1e3 g", "Serving size: 1,00 medium banana")) {
            assertNull(basis, estimate(source(basis = basis), amount = 100.0, unit = "g").kcal)
            assertUnknown(source(basis = basis))
        }
    }

    @Test fun decimalAndExplicitZeroRemainSupportedWithOriginalEvidence() {
        for ((row, expected) in listOf("Calories: 100.5 kcal" to 100.5, "Calories: 0 kcal" to 0.0)) {
            val page = source(calories = row)
            val value = estimate(page)
            assertEquals(expected, value.kcal!!, 0.001)
            val quote = value.evidence.single()
            assertEquals("snapshot", quote.snapshotId)
            assertEquals(page.id, quote.sourceId)
            assertTrue(page.excerpt.contains(quote.excerpt))
            assertTrue(quote.excerpt.contains(row))
        }
    }

    // Literal, synthetic examples of the observed three-column extraction contract. Not a page copy.
    private fun myFoodData(food: String = "Banana raw", basis: String = "100g", calories: String = "89",
        dataSource: String = "USDA Standard Release", macros: String = """
            | Total Fat 0.33g | | 0% |
            | Total Carbohydrate 22.84g | | 8% |
            | Protein 1.09g | | 2% |
        """.trimIndent(), after: String = ""): RetrievedSource = RetrievedSource(
        "mfd", "https://tools.myfooddata.com/nutrition-facts/fixture/100g", "Nutrition Facts for $food", "2026-10-04T10:00:00Z",
        publisher = "MyFoodData", kind = "nutrition", excerpt = """
            # Nutrition Facts Search Tool
            # $food
            Data Source: $dataSource
            Serving Size:

            | Nutrition Facts | | |
            | --- | --- | --- |
            | Serving Size | | |
            | $basis | | |
            | Calories | | $calories |
        """.trimIndent() + "\n$macros\n\n$after")

    @Test fun reviewedMyFoodDataLabelReadsOnlyAbsoluteValuesAndPreservesExactTable() {
        val page = myFoodData()
        val result = estimate(page, "banana raw", 100.0, "g")
        assertEquals(89.0, result.kcal!!, 0.001)
        assertEquals(0.33, result.fat!!, 0.001)
        assertEquals(22.84, result.carbs!!, 0.001)
        assertEquals(1.09, result.protein!!, 0.001)
        assertEquals(EstimateStatus.ESTIMATED, result.status)
        assertTrue(result.explanation.contains("USDA Standard Release"))
        assertTrue(result.explanation.contains("not independently verified"))
        val quote = result.evidence.single()
        assertEquals("snapshot", quote.snapshotId)
        assertEquals(page.id, quote.sourceId)
        assertTrue(page.excerpt.contains(quote.excerpt))
        assertTrue(quote.excerpt.startsWith("| Nutrition Facts | | |"))
        assertTrue(quote.excerpt.contains("| Total Fat 0.33g | | 0% |"))
        assertEquals(44.5, estimate(page, "banana raw", 50.0, "g").kcal!!, 0.001)
    }

    @Test fun myFoodDataNeverBorrowsUnknownPieceWeightsFromMassOrLaterTables() {
        val page = myFoodData(after = "| Serving Size | 1 piece (100g) | |\n| Calories | | 500 |")
        assertNull(estimate(page, "banana raw").kcal)
        val piece = myFoodData(basis = "1 piece (118g)")
        assertEquals(178.0, estimate(piece, "banana raw", 2.0).kcal!!, 0.001)
        assertEquals(44.5, estimate(piece, "banana raw", 59.0, "g").kcal!!, 0.001)
        assertNull(estimate(myFoodData(basis = "1 piece"), "banana raw", 59.0, "g").kcal)
        assertEquals(89.0, estimate(myFoodData(basis = "1 piece"), "banana raw").kcal!!, 0.001)
    }

    @Test fun repeatedEquivalentMassIsAcceptedButContradictoryMassNeverIs() {
        val page = myFoodData(basis = "100 grams (100g)")
        val result = estimate(page, "banana raw", 50.0, "g")
        assertEquals(44.5, result.kcal!!, 0.001)
        assertTrue(result.evidence.single().excerpt.contains("100 grams (100g)"))
        assertNull(estimate(page, "banana raw", 1.0, "piece").kcal)
        assertNull(estimate(myFoodData(basis = "100 grams (200g)"), "banana raw", 50.0, "g").kcal)
    }

    @Test fun secondaryTablesAndCaloriePercentagesCannotReplaceFirstLabelMetrics() {
        val page = myFoodData(macros = """
            | Total Fat 0.33g | | 0% |
            | Calories from Fat | | 3% |
            | Protein | | 2% |
            | Total Carbohydrate 22.84g | | 8% |
        """.trimIndent(), after = """
            ## Detailed Nutrition Facts
            | Nutrient | Amount | Per 100g | % Calories |
            | Calories | 999 | 999 | 100% |
            | Protein | 99g | 99g | 80% |
        """.trimIndent())
        val result = estimate(page, "banana raw", 100.0, "g")
        assertEquals(89.0, result.kcal!!, 0.001)
        assertEquals(0.33, result.fat!!, 0.001)
        assertNull(result.protein)
        assertFalse(result.evidence.single().excerpt.contains("999"))
        assertFalse(result.evidence.single().excerpt.contains("Detailed Nutrition Facts"))
        val missing = myFoodData(calories = "8%", after = "| Calories | | 89 |")
        assertNull(estimate(missing, "banana raw", 100.0, "g").kcal)
    }

    @Test fun myFoodDataRequiresExactTitleAndFoodHeadingNotOnlyGenericToolHeading() {
        val page = myFoodData()
        assertNull(estimate(page, "banana", 100.0, "g").kcal)
        assertNull(estimate(page.copy(title = "Nutrition Facts Search Tool"), "banana raw", 100.0, "g").kcal)
        assertNull(estimate(page.copy(excerpt = page.excerpt.replace("# Banana raw\n", "")), "banana raw", 100.0, "g").kcal)
        assertNull(estimate(page.copy(excerpt = page.excerpt.replace("# Banana raw", "# Banana bread")), "banana raw", 100.0, "g").kcal)
        assertNull(estimate(page.copy(title = "Nutrition Facts for Banana bread"), "banana raw", 100.0, "g").kcal)
    }

    @Test fun kachoriSearchNearMatchesNeverBecomePyaazKachoriNutrition() {
        for (other in listOf("Dry-fruit kachori", "Puri", "Kolachi", "Kachori", "Onion kachori chaat")) {
            val page = myFoodData(food = other)
            assertNull(other, estimate(page, "pyaaz kachori", 100.0, "g").kcal)
            assertNull(other, estimate(page.copy(title = "Nutrition Facts for Pyaaz kachori"), "pyaaz kachori", 100.0, "g").kcal)
        }
        assertEquals(89.0, estimate(myFoodData(food = "Onion kachoris"), "pyaaz kachori", 100.0, "g").kcal!!, 0.001)
    }

    @Test fun myFoodDataExtensionIsHostKindAndSourceDatasetBounded() {
        val page = myFoodData()
        for (url in listOf("https://example.org/nutrition", "https://tools.myfooddata.com.example.org/nutrition",
            "http://tools.myfooddata.com/nutrition", "https://tools.myfooddata.com:8443/nutrition")) {
            assertNull(url, estimate(page.copy(url = url), "banana raw", 100.0, "g").kcal)
        }
        for (kind in listOf("recipe", "manufacturer", "legacy")) assertNull(estimate(page.copy(kind = kind), "banana raw", 100.0, "g").kcal)
        for (dataset in listOf("User_Entered", "User Entered", "Unknown", "USDA Standard Release and User_Entered")) {
            assertNull(dataset, estimate(myFoodData(dataSource = dataset), "banana raw", 100.0, "g").kcal)
        }
        assertNull(estimate(page.copy(excerpt = page.excerpt.replace("Data Source: USDA Standard Release", "")), "banana raw", 100.0, "g").kcal)
    }

    @Test fun myFoodDataGroupedNumbersAndMalformedNumbersKeepTheStrictNumericContract() {
        for ((value, expected) in listOf("1,200" to 1200.0, "1,000" to 1000.0)) {
            val result = estimate(myFoodData(calories = value), "banana raw", 100.0, "g")
            assertEquals(expected, result.kcal!!, 0.001)
            assertTrue(result.evidence.single().excerpt.contains("| Calories | | $value |"))
        }
        for (value in listOf("1,20", "1,,000", "1e3", "1 000", "1.2.3", "-200", "8%")) {
            assertNull(value, estimate(myFoodData(calories = value), "banana raw", 100.0, "g").kcal)
        }
        for (basis in listOf("1,00g", "1 cup", "100g (1 piece)", "1 piece (1,18g)", "1 piece of banana bread")) {
            assertNull(basis, estimate(myFoodData(basis = basis), "banana raw", 100.0, "g").kcal)
            assertNull(basis, estimate(myFoodData(basis = basis), "banana raw").kcal)
        }
    }

    @Test fun malformedFirstTableCannotFallThroughToASecondUsableTableOrProse() {
        val page = myFoodData()
        val bad = page.copy(excerpt = page.excerpt.replace("| 100g | | |", "| 100g | 200g | |").trimEnd() +
            "\n\nServing size: 1 medium banana\nCalories: 500 kcal\n\n" + page.excerpt.substringAfter("Serving Size:\n\n"))
        assertNull(estimate(bad, "banana raw", 100.0, "g").kcal)
        val duplicate = page.copy(excerpt = page.excerpt.replace("| Protein 1.09g | | 2% |", "| Calories | | 500 |"))
        assertNull(estimate(duplicate, "banana raw", 100.0, "g").kcal)
        val oversized = page.copy(excerpt = page.excerpt.replace("| Protein 1.09g | | 2% |", "| Other | | |\n".repeat(70)))
        assertNull(estimate(oversized, "banana raw", 100.0, "g").kcal)
        val brokenRow = page.copy(excerpt = page.excerpt.replace("| Protein 1.09g | | 2% |", "|"))
        assertNull(estimate(brokenRow, "banana raw", 100.0, "g").kcal)
    }
}
