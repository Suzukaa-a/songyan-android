package app.songyan.ime.content

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.Base64

/** Behaviour of iOS `LearningBackupArchiveTests`, plus reading a file in the exact iOS encoding. */
class LearningBackupArchiveTest {
    private val fixedDate: Instant = Instant.ofEpochSecond(1_767_225_600) // 2026-01-01T00:00:00Z

    private fun dictionary(
        name: String,
        text: String,
    ) = LearningBackupArchive.Dictionary(name, text.toByteArray(Charsets.UTF_8))

    private fun archive(
        dictionaries: List<LearningBackupArchive.Dictionary>,
        sourceId: String = "rime-ice",
        revision: String = "rev-2026-01-01",
        sourceName: String = "雾凇拼音",
        engineVersion: String = "1.9.0",
        createdAt: Instant = fixedDate,
    ) = LearningBackupArchive(sourceId, revision, sourceName, engineVersion, createdAt, dictionaries)

    private fun assertRefused(
        expected: LearningBackupError,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("expected $expected")
        } catch (e: LearningBackupException) {
            assertEquals(expected, e.error)
        }
    }

    private fun sha(text: String) = LearningBackupArchive.sha256Hex(text.toByteArray(Charsets.UTF_8))

    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun raw(
        version: String = "1",
        producer: String = "\"keyboard-extension\"",
        sourceId: String = "\"rime-ice\"",
        createdAt: String = "\"2026-01-01T00:00:00Z\"",
        dictionaries: String = """[{"name":"words","sha256":"${sha("a")}","data":"${base64("a")}"}]""",
    ): ByteArray =
        (
            """{"version":$version,"producer":$producer,"sourceID":$sourceId,"resourceRevision":"rev-1",""" +
                """"sourceName":"雾凇拼音","engineVersion":"1.9.0","createdAt":$createdAt,"dictionaries":$dictionaries}"""
        ).toByteArray(Charsets.UTF_8)

    @Test
    fun roundTripKeepsTheArchiveExactly() {
        val original = archive(listOf(dictionary("words", "词条一\n词条二"), dictionary("emoji-🀄️", "🀄️🀄️")))
        val decoded = LearningBackupArchive.decode(original.encoded())
        assertEquals(original, decoded)
        assertEquals(2, decoded.dictionaries.size)
        assertEquals("rev-2026-01-01", decoded.resourceRevision)
    }

    @Test
    fun encodingIsDeterministicSortedAndUsesWholeSecondIso8601() {
        val original = archive(listOf(dictionary("words", "a")))
        assertArrayEquals(original.encoded(), original.encoded())
        val text = String(original.encoded(), Charsets.UTF_8)
        assertTrue(text, text.contains("\"createdAt\":\"2026-01-01T00:00:00Z\""))
        val keys =
            listOf(
                "createdAt",
                "dictionaries",
                "engineVersion",
                "producer",
                "resourceRevision",
                "sourceID",
                "sourceName",
                "version",
            )
        val positions = keys.map { text.indexOf("\"$it\":") }
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun subSecondTimestampIsNormalizedAndStillRoundTrips() {
        val original = archive(listOf(dictionary("words", "a")), createdAt = fixedDate.plusMillis(750))
        assertEquals(fixedDate, original.createdAt)
        assertEquals(original, LearningBackupArchive.decode(original.encoded()))
    }

    @Test
    fun digestIsLowercaseSha256OfTheData() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha("abc"))
        assertEquals(sha("abc"), dictionary("words", "abc").sha256)
    }

    @Test
    fun readsAnArchiveEncodedLikeIosJsonEncoder() {
        // Swift's JSONEncoder escapes "/" as "\/"; base64 data contains slashes, so the escape must decode.
        val text = "???" + "ÿ".repeat(3)
        val data = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        assertTrue("fixture must exercise an escaped slash", '/' in data)
        val escaped = data.replace("/", "\\/")
        val json =
            """{"createdAt":"2026-01-01T08:00:00+08:00","dictionaries":[{"data":"$escaped","name":"songyan_shared",""" +
                """"sha256":"${sha(text)}"}],"engineVersion":"1.17.0","producer":"keyboard-extension",""" +
                """"resourceRevision":"abc","sourceID":"userScheme:songyan-builtin","sourceName":"内置输入方案","version":1}"""
        val decoded = LearningBackupArchive.decode(json.toByteArray(Charsets.UTF_8))
        assertEquals(fixedDate, decoded.createdAt)
        assertEquals(text, String(decoded.dictionaries.single().data, Charsets.UTF_8))
        assertEquals("userScheme:songyan-builtin", decoded.sourceId)
    }

    @Test
    fun tamperedDigestOrContentIsRefusedWithItsIndex() {
        val good = """{"name":"a","sha256":"${sha("a")}","data":"${base64("a")}"}"""
        val tampered = """{"name":"b","sha256":"${sha("b")}","data":"${base64("x")}"}"""
        try {
            LearningBackupArchive.decode(raw(dictionaries = "[$good,$tampered]"))
            fail()
        } catch (e: LearningBackupException) {
            assertEquals(LearningBackupError.DIGEST_MISMATCH, e.error)
            assertEquals(1, e.dictionaryIndex)
        }
    }

    @Test
    fun uppercaseOrWrongLengthDigestIsRefused() {
        val upper = sha("a").uppercase()
        assertRefused(LearningBackupError.MALFORMED_DIGEST) {
            LearningBackupArchive.decode(
                raw(dictionaries = """[{"name":"w","sha256":"$upper","data":"${base64("a")}"}]"""),
            )
        }
        assertRefused(LearningBackupError.MALFORMED_DIGEST) {
            LearningBackupArchive.decode(
                raw(dictionaries = """[{"name":"w","sha256":"abc","data":"${base64("a")}"}]"""),
            )
        }
    }

    @Test
    fun invalidBase64IsRefused() {
        assertRefused(LearningBackupError.MALFORMED) {
            LearningBackupArchive.decode(
                raw(dictionaries = """[{"name":"w","sha256":"${sha("a")}","data":"*not base64*"}]"""),
            )
        }
    }

    @Test
    fun futureVersionAndForeignProducerAreRefused() {
        assertRefused(LearningBackupError.UNSUPPORTED_VERSION) { LearningBackupArchive.decode(raw(version = "2")) }
        assertRefused(
            LearningBackupError.UNEXPECTED_PRODUCER,
        ) { LearningBackupArchive.decode(raw(producer = "\"host-app\"")) }
    }

    @Test
    fun missingFieldsWrongTypesAndNonJsonAreRefused() {
        assertRefused(LearningBackupError.MISSING_FIELD) { LearningBackupArchive.decode(raw(sourceId = "null")) }
        assertRefused(LearningBackupError.MALFORMED) { LearningBackupArchive.decode(raw(version = "\"1\"")) }
        assertRefused(LearningBackupError.MALFORMED) { LearningBackupArchive.decode(raw(dictionaries = "{}")) }
        assertRefused(LearningBackupError.MALFORMED) { LearningBackupArchive.decode(raw(createdAt = "\"2026-01-01\"")) }
        assertRefused(LearningBackupError.MALFORMED) {
            LearningBackupArchive.decode(raw(createdAt = "\"2026-01-01T00:00:00.5Z\""))
        }
        assertRefused(LearningBackupError.MALFORMED) { LearningBackupArchive.decode("not json".toByteArray()) }
        assertRefused(LearningBackupError.MALFORMED) { LearningBackupArchive.decode(byteArrayOf(0xff.toByte(), 0x7b)) }
        assertRefused(LearningBackupError.MALFORMED) { LearningBackupArchive.decode("[1]".toByteArray()) }
        // A truncated file (an interrupted save) is refused, never half-read.
        val whole = archive(listOf(dictionary("words", "a"))).encoded()
        assertRefused(LearningBackupError.MALFORMED) { LearningBackupArchive.decode(whole.copyOf(whole.size / 2)) }
    }

    @Test
    fun dictionaryCountBounds() {
        assertRefused(LearningBackupError.EMPTY_DICTIONARIES) { archive(emptyList()) }
        archive((0 until 32).map { dictionary("d$it", "x") })
        assertRefused(
            LearningBackupError.TOO_MANY_DICTIONARIES,
        ) { archive((0 until 33).map { dictionary("d$it", "x") }) }
        val item = """{"name":"w","sha256":"${sha("a")}","data":"${base64("a")}"}"""
        assertRefused(LearningBackupError.TOO_MANY_DICTIONARIES) {
            LearningBackupArchive.decode(raw(dictionaries = (0 until 33).joinToString(",", "[", "]") { item }))
        }
    }

    @Test
    fun duplicateNamesIncludingCaseAndNormalizationAreRefused() {
        assertRefused(LearningBackupError.DUPLICATE_DICTIONARY_NAME) {
            archive(listOf(dictionary("w", "a"), dictionary("w", "b")))
        }
        assertRefused(LearningBackupError.DUPLICATE_DICTIONARY_NAME) {
            archive(listOf(dictionary("Words", "a"), dictionary("words", "b")))
        }
        assertRefused(LearningBackupError.DUPLICATE_DICTIONARY_NAME) {
            archive(listOf(dictionary("café", "a"), dictionary("cafe\u0301", "b")))
        }
    }

    @Test
    fun namesThatCouldEscapeOrHideAreRefused() {
        for (name in listOf(
            "",
            "../x",
            "a/b",
            "a\\b",
            ".",
            "..",
            ".hidden",
            "a\u0000b",
            "a\nb",
            "a\u200Bb",
            "x".repeat(257),
        )) {
            assertRefused(LearningBackupError.INVALID_DICTIONARY_NAME) { dictionary(name, "a") }
        }
        assertRefused(LearningBackupError.INVALID_DICTIONARY_NAME) {
            LearningBackupArchive.decode(
                raw(dictionaries = """[{"name":"../x","sha256":"${sha("a")}","data":"${base64("a")}"}]"""),
            )
        }
        for (name in listOf("词库", "emoji-🀄️", "é", "a b", "x".repeat(256))) dictionary(name, "a")
    }

    @Test
    fun metadataBounds() {
        val words = listOf(dictionary("w", "a"))
        assertRefused(LearningBackupError.EMPTY_METADATA) { archive(words, sourceId = "") }
        assertRefused(LearningBackupError.METADATA_TOO_LONG) { archive(words, sourceId = "x".repeat(513)) }
        archive(words, sourceId = "x".repeat(512))
        assertRefused(LearningBackupError.METADATA_TOO_LONG) { archive(words, sourceName = "名".repeat(86)) }
        assertRefused(LearningBackupError.METADATA_TOO_LONG) { archive(words, engineVersion = "1".repeat(129)) }
        assertRefused(LearningBackupError.METADATA_CONTROL_CHARACTER) { archive(words, sourceName = "a\tb") }
        assertRefused(LearningBackupError.METADATA_CONTROL_CHARACTER) {
            LearningBackupArchive.decode(raw(sourceId = "\"a\\u0007b\""))
        }
    }

    @Test
    fun sizeBoundsPerDictionaryTotalAndInput() {
        val four = LearningBackupArchive.MAXIMUM_DICTIONARY_BYTES
        LearningBackupArchive.Dictionary("w", ByteArray(four))
        assertRefused(LearningBackupError.DICTIONARY_TOO_LARGE) {
            archive(
                listOf(
                    LearningBackupArchive.Dictionary(
                        "w",
                        ByteArray(
                            four + 1,
                        ),
                    ),
                ),
            )
        }
        val halves = (0 until 3).map { LearningBackupArchive.Dictionary("d$it", ByteArray(3 * 1024 * 1024)) }
        assertRefused(LearningBackupError.DICTIONARIES_TOO_LARGE) { archive(halves) }
        val exactlyEight =
            listOf(
                LearningBackupArchive.Dictionary("a", ByteArray(four)),
                LearningBackupArchive.Dictionary("b", ByteArray(four)),
            )
        val encoded = archive(exactlyEight).encoded()
        assertEquals(archive(exactlyEight), LearningBackupArchive.decode(encoded))
        assertRefused(LearningBackupError.ENCODED_TOO_LARGE) {
            LearningBackupArchive.decode(
                ByteArray(LearningBackupArchive.MAXIMUM_ENCODED_BYTES + 1) { ' '.code.toByte() },
            )
        }
    }

    @Test
    fun errorsNameNoDictionaryOrEntry() {
        val message =
            try {
                LearningBackupArchive.decode(
                    raw(dictionaries = """[{"name":"秘密词库","sha256":"${sha("b")}","data":"${base64("私人词条")}"}]"""),
                )
                ""
            } catch (e: LearningBackupException) {
                e.message.orEmpty()
            }
        assertTrue(message, message.isNotEmpty() && "秘密" !in message && "私人" !in message)
    }
}
