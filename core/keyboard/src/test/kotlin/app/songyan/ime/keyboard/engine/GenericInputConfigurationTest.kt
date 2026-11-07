package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.support.UserTerminology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout availability for the extended input languages: which shipped key shapes and which
 * scheme-driven physical layouts each scheme family may use, and that a scheme's own declarations
 * never override the family rule. The cases mirror the iOS GenericInputConfigurationTests.
 */
class GenericInputConfigurationTest {
    private val restrictedFamilies =
        listOf(
            InputSchemeFamily.ZHUYIN,
            InputSchemeFamily.CANGJIE_RADICALS,
            InputSchemeFamily.JAPANESE,
        )

    // The Dachen key map: the alphabet rule of the language table.
    private val bopomofoAlphabet = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-"

    private fun schema(
        id: String,
        alphabet: String = "",
    ) = KeyboardSchemaDescriptor(id = id, name = id, alphabet = alphabet)

    private fun set(
        schemas: List<KeyboardSchemaDescriptor>,
        adapters: List<KeyboardResourceSet.EncodingAdapter> = emptyList(),
        published: List<PublishedKeyboard>? = null,
    ) = KeyboardResourceSet(
        id = "test",
        name = "测试来源",
        sharedDirectory = "/shared",
        userDirectory = "/user",
        layoutEntries = schemas.map { KeyboardResourceSet.LayoutEntry(KeyboardLayoutKind.QWERTY26, it.id) },
        schemas = schemas,
        publishedKeyboards = published,
        encodingAdapters = adapters,
    )

    // MARK: - The rule table

    @Test
    fun aLatinPhoneticSchemeFoldsLikeAnyOther() {
        // Jyutping is typed on Latin letters: a 9-key or 14-key keyboard saved for it keeps working.
        for (kind in KeyboardLayoutKind.entries) {
            assertTrue("$kind", InputLayoutPolicy.allows(kind, InputSchemeFamily.LATIN_PHONETIC))
        }
    }

    @Test
    fun aRestrictedFamilyKeepsOnlyTheIdentityShape() {
        for (family in restrictedFamilies) {
            assertTrue(
                "$family must keep the identity shape",
                InputLayoutPolicy.allows(KeyboardLayoutKind.QWERTY26, family),
            )
            for (kind in listOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9)) {
                assertFalse("$family must not use $kind", InputLayoutPolicy.allows(kind, family))
            }
        }
    }

    @Test
    fun anUnknownFamilyKeepsEveryShippedShape() {
        for (kind in KeyboardLayoutKind.entries) {
            assertTrue("$kind keeps today's behavior", InputLayoutPolicy.allows(kind, InputSchemeFamily.GENERIC))
        }
    }

    @Test
    fun aReservedPhysicalLayoutBelongsToExactlyOneFamily() {
        val owners =
            mapOf(
                InputLayoutPolicy.ZHUYIN_LAYOUT to InputSchemeFamily.ZHUYIN,
                InputLayoutPolicy.CANGJIE_LAYOUT to InputSchemeFamily.CANGJIE_RADICALS,
                InputLayoutPolicy.ROMAJI_LAYOUT to InputSchemeFamily.JAPANESE,
                InputLayoutPolicy.KANA_LAYOUT to InputSchemeFamily.JAPANESE,
            )
        for (family in InputSchemeFamily.entries) {
            for ((layoutID, owner) in owners) {
                assertEquals("$layoutID for $family", family == owner, InputLayoutPolicy.allows(layoutID, family))
            }
        }
    }

    @Test
    fun anUnknownPhysicalLayoutKeepsLegacyBehavior() {
        for (family in InputSchemeFamily.entries) {
            assertTrue(InputLayoutPolicy.allows("qwerty26", family))
            assertTrue(InputLayoutPolicy.allows("my-own-layout", family))
            assertTrue(InputLayoutPolicy.allows("", family))
        }
    }

    @Test
    fun theFamilyPreferenceNamesTheSchemesOwnPhysicalLayout() {
        assertEquals(InputLayoutPolicy.ZHUYIN_LAYOUT, InputLayoutPolicy.preferredLayoutID(InputSchemeFamily.ZHUYIN))
        assertEquals(
            InputLayoutPolicy.CANGJIE_LAYOUT,
            InputLayoutPolicy.preferredLayoutID(InputSchemeFamily.CANGJIE_RADICALS),
        )
        assertEquals(InputLayoutPolicy.ROMAJI_LAYOUT, InputLayoutPolicy.preferredLayoutID(InputSchemeFamily.JAPANESE))
        assertNull(InputLayoutPolicy.preferredLayoutID(InputSchemeFamily.LATIN_PHONETIC))
        assertNull(InputLayoutPolicy.preferredLayoutID(InputSchemeFamily.GENERIC))
    }

    @Test
    fun theFamilyComesFromTheSchemaIdBeforeItsAlphabet() {
        assertEquals(InputSchemeFamily.JAPANESE, InputLayoutPolicy.familyOf("kagiroi"))
        assertEquals(InputSchemeFamily.ZHUYIN, InputLayoutPolicy.familyOf("bopomofo"))
        assertEquals(InputSchemeFamily.ZHUYIN, InputLayoutPolicy.familyOf("bopomofo_tw"))
        assertEquals(InputSchemeFamily.CANGJIE_RADICALS, InputLayoutPolicy.familyOf("cangjie5"))
        assertEquals(InputSchemeFamily.CANGJIE_RADICALS, InputLayoutPolicy.familyOf("quick5"))
        assertEquals(InputSchemeFamily.LATIN_PHONETIC, InputLayoutPolicy.familyOf("jyut6ping3"))
        assertEquals(InputSchemeFamily.GENERIC, InputLayoutPolicy.familyOf("rime_ice"))
        assertEquals(InputSchemeFamily.GENERIC, InputLayoutPolicy.familyOf(""))
        // An id the table does not know is still recognized by the Dachen alphabet.
        assertEquals(InputSchemeFamily.ZHUYIN, InputLayoutPolicy.familyOf("dachen", bopomofoAlphabet))
        assertEquals(InputSchemeFamily.GENERIC, InputLayoutPolicy.familyOf("dachen"))
        // A known id wins over whatever alphabet a scheme declares.
        assertEquals(InputSchemeFamily.JAPANESE, InputLayoutPolicy.familyOf("kagiroi", bopomofoAlphabet))
    }

    // MARK: - The resource set applies the rule before any declaration

    @Test
    fun aDeclaredCompactCompanionDoesNotOverrideTheFamilyRule() {
        val cangjie = schema("cangjie5", "abcdefghijklmnopqrstuvwxyz")
        val declared =
            set(
                listOf(cangjie),
                adapters =
                    listOf(
                        KeyboardResourceSet.EncodingAdapter(
                            "cangjie5",
                            "keyboard_0123456789abcdef0123_9",
                            KeyboardLayoutKind.PHONE9,
                            "倉頡",
                        ),
                    ),
            )
        assertFalse(
            "Cangjie must not run on 9 keys even when a companion was declared",
            declared.availableCombinations.any { it.layout == KeyboardLayoutKind.PHONE9 },
        )
        assertEquals(
            UserTerminology.layoutUnavailableReason(KeyboardLayoutKind.PHONE9),
            declared.incompatibilityReason(KeyboardLayoutKind.PHONE9, "cangjie5"),
        )
        assertNull(declared.logicalSchemaID("cangjie5", KeyboardLayoutKind.PHONE9))
        assertEquals(listOf(KeyboardLayoutKind.QWERTY26), declared.layoutsFor("cangjie5"))

        // The same declaration on a scheme without a family keeps today's availability.
        val ice =
            set(
                listOf(schema("rime_ice", "abcdefghijklmnopqrstuvwxyz")),
                adapters =
                    listOf(
                        KeyboardResourceSet.EncodingAdapter(
                            "rime_ice",
                            "keyboard_0123456789abcdef0123_9",
                            KeyboardLayoutKind.PHONE9,
                            "雾凇",
                        ),
                    ),
            )
        assertTrue(
            "an unknown scheme keeps the declared 9-key shape",
            ice.availableCombinations.any { it.schemaID == "rime_ice" && it.layout == KeyboardLayoutKind.PHONE9 },
        )
    }

    @Test
    fun anAuthorsLayoutEntryDoesNotOverrideTheFamilyRule() {
        val cangjie = schema("cangjie5", "abcdefghijklmnopqrstuvwxyz")
        val declared =
            KeyboardResourceSet(
                id = "test",
                name = "测试来源",
                sharedDirectory = "/shared",
                userDirectory = "/user",
                layoutEntries =
                    listOf(
                        KeyboardResourceSet.LayoutEntry(KeyboardLayoutKind.QWERTY26, "cangjie5"),
                        KeyboardResourceSet.LayoutEntry(KeyboardLayoutKind.MERGED14, "cangjie5"),
                    ),
                schemas = listOf(cangjie),
            )
        assertFalse(
            "a layout entry must not grant a shape the family forbids",
            declared.availableCombinations.any { it.layout == KeyboardLayoutKind.MERGED14 },
        )
        assertNull(declared.logicalSchemaID("cangjie5", KeyboardLayoutKind.MERGED14))
    }

    @Test
    fun aPublishedPairingDoesNotOverrideTheFamilyRule() {
        val published =
            set(
                listOf(schema("bopomofo_tw", bopomofoAlphabet)),
                published =
                    listOf(
                        PublishedKeyboard("bopomofo_tw", KeyboardLayoutKind.PHONE9),
                        PublishedKeyboard("bopomofo_tw", KeyboardLayoutKind.QWERTY26),
                    ),
            )
        assertFalse(
            "a published pairing must not grant a folded shape to a zhuyin scheme",
            published.availableCombinations.any { it.layout == KeyboardLayoutKind.PHONE9 },
        )
        assertTrue(published.availableCombinations.any { it.layout == KeyboardLayoutKind.QWERTY26 })
    }

    @Test
    fun theFamilyComesFromTheSchemasDeclaredAlphabet() {
        val adapter =
            KeyboardResourceSet.EncodingAdapter(
                "dachen",
                "keyboard_0123456789abcdef0123_9",
                KeyboardLayoutKind.PHONE9,
                "注音",
            )
        val withAlphabet = set(listOf(schema("dachen", bopomofoAlphabet)), adapters = listOf(adapter))
        assertEquals(InputSchemeFamily.ZHUYIN, withAlphabet.familyOf("dachen"))
        assertFalse(withAlphabet.availableCombinations.any { it.layout == KeyboardLayoutKind.PHONE9 })

        // Without the declared alphabet the id proves nothing, so the legacy availability stays.
        val idOnly = set(listOf(schema("dachen")), adapters = listOf(adapter))
        assertEquals(InputSchemeFamily.GENERIC, idOnly.familyOf("dachen"))
        assertTrue(idOnly.availableCombinations.any { it.layout == KeyboardLayoutKind.PHONE9 })
    }

    @Test
    fun theBundledSchemesKeepEveryShippedShape() {
        val builtin = KeyboardResourceSet.builtin("/shared", "/user")
        assertEquals(KeyboardLayoutKind.entries.toSet(), builtin.availableLayouts.toSet())
        for (kind in KeyboardLayoutKind.entries) {
            assertNull(builtin.incompatibilityReason(kind, BuiltinTemplateSchemas.QUANPIN))
        }
    }

    @Test
    fun anEmptySchemaAlphabetDoesNotHideTheKnownDescriptorFact() {
        // The engine list may report no alphabet while the resource facts carry the deployed one:
        // an empty known value is "unknown" and must not mask the real fact.
        val dachen = schema("dachen")
        val set =
            KeyboardResourceSet(
                id = "test",
                name = "测试来源",
                sharedDirectory = "/shared",
                userDirectory = "/user",
                layoutEntries = listOf(KeyboardResourceSet.LayoutEntry(KeyboardLayoutKind.QWERTY26, "dachen")),
                schemas = listOf(dachen),
                schemaFacts = listOf(schema("dachen", bopomofoAlphabet)),
            )
        assertEquals(InputSchemeFamily.ZHUYIN, set.familyOf("dachen"))
        assertFalse(
            "a zhuyin scheme cannot use a folded shape",
            set.availableCombinations.any { it.layout != KeyboardLayoutKind.QWERTY26 },
        )
        assertTrue(set.availableCombinations.any { it.layout == KeyboardLayoutKind.QWERTY26 })
    }

    @Test
    fun aGeneratedCompanionIdAnswersWithItsSourcesFamily() {
        val adapter =
            KeyboardResourceSet.EncodingAdapter(
                "dachen",
                "keyboard_0123456789abcdef0123_9",
                KeyboardLayoutKind.PHONE9,
                "注音",
            )
        val set = set(listOf(schema("dachen", bopomofoAlphabet)), adapters = listOf(adapter))
        assertEquals(InputSchemeFamily.ZHUYIN, set.familyOf("keyboard_0123456789abcdef0123_9"))
        assertEquals(InputSchemeFamily.ZHUYIN, set.familyOf("dachen"))
    }

    @Test
    fun theUnavailableReasonSpeaksOfTheShapeInUserWords() {
        val set = set(listOf(schema("kagiroi")))
        val reason = set.incompatibilityReason(KeyboardLayoutKind.PHONE9, "kagiroi")
        assertEquals(UserTerminology.layoutUnavailableReason(KeyboardLayoutKind.PHONE9), reason)
        assertNull(UserTerminology.leaksInternalTerminology(reason.orEmpty()))
    }
}
