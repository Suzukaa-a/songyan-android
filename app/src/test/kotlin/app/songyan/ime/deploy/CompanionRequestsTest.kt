package app.songyan.ime.deploy

import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.UserKeyboards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRequestsTest {
    private val folds = setOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9)

    private fun k(id: String) = requireNotNull(PublishedKeyboard.parse(id))

    @Test
    fun anInputMethodTheListNeverSpokeForIsAskedForTheFoldsTheOldMenuOffered() {
        val requests = CompanionRequests(hiddenLayouts = setOf("merged14"), hiddenSchemas = setOf("hidden"))
        assertEquals(setOf(KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9), requests.kinds("a"))
        assertTrue(requests.kinds("hidden").isEmpty())
    }

    @Test
    fun aFirstInstallCoversItsInputMethodsWithNoKeyboardsAndKeepsWhatWasThere() {
        val file =
            kotlin.io.path
                .createTempFile("requests")
                .toFile()
        try {
            file.delete()
            CompanionRequests.cover(file, listOf("new"))
            CompanionRequests.cover(file, listOf("other"))
            val read = CompanionRequests.read(file)
            assertTrue(read.kinds("new").isEmpty() && read.kinds("other").isEmpty())
            assertEquals(folds, read.kinds("older"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun aPreferenceWriteKeepsWhatAFirstInstallCovered() {
        val file =
            kotlin.io.path
                .createTempFile("requests")
                .toFile()
        try {
            file.delete()
            CompanionRequests.cover(file, listOf("new"))
            val stored = UserKeyboards.Stored(listOf(k("a:phone9#phone9")), setOf("a"))
            CompanionRequests.mirror(file, CompanionRequests(stored, hiddenLayouts = setOf("merged14")))
            val read = CompanionRequests.read(file)
            assertTrue(read.kinds("new").isEmpty())
            assertEquals(setOf(KeyboardLayoutKind.PHONE9), read.kinds("a"))
            assertEquals(setOf(KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9), read.kinds("older"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun aStoredInputMethodIsAskedForExactlyItsKeyboardsFolds() {
        val stored =
            UserKeyboards.Stored(
                listOf(k("a:qwerty26#qwerty26"), k("a:phone9#phone9"), k("b:merged14#merged14")),
                setOf("a", "c"),
            )
        val requests = CompanionRequests(stored)
        assertEquals(setOf(KeyboardLayoutKind.PHONE9), requests.kinds("a"))
        assertTrue(requests.kinds("c").isEmpty())
        assertEquals(folds, requests.kinds("b"))
        assertEquals(
            mapOf("a" to setOf(CompanionShape.builtin(KeyboardLayoutKind.PHONE9))),
            requests.requested(listOf("a", "c")),
        )
    }

    @Test
    fun theMirrorRoundTripsAndAnUnreadableOneReadsAsNeverEdited() {
        val requests =
            CompanionRequests(
                UserKeyboards.Stored(listOf(k("a:merged14#my:layout#1")), setOf("a")),
                setOf("h"),
                setOf("phone9"),
            )
        assertEquals(requests, CompanionRequests.decode(requests.encode()))
        assertNull(CompanionRequests.decode("not json"))
    }

    @Test
    fun missingIsWhatIsAskedForAndNeitherBuiltNorRefused() {
        val asked = mapOf("a" to folds.mapTo(LinkedHashSet()) { requireNotNull(CompanionShape.builtin(it)) })
        val built = listOf(KeyboardResourceSet.EncodingAdapter("a", "x14", KeyboardLayoutKind.MERGED14))
        val refused = mapOf("a" to setOf("phone9"))
        assertEquals(
            mapOf("a" to setOf(CompanionShape.builtin(KeyboardLayoutKind.MERGED18))),
            CompanionStatus.missing(asked, built, refused),
        )
        assertTrue(CompanionStatus.missing(asked, built, mapOf("a" to setOf("phone9", "merged18"))).isEmpty())
    }

    @Test
    fun aKeyboardOnALayoutOfItsOwnAsksForItsOwnFold() {
        val custom =
            CompactFold.MERGED14.map
                .toMutableMap()
                .apply { put('w', 'e') }
        val fold = requireNotNull(CompactFold.of(KeyboardLayoutKind.MERGED14, custom))
        val targets = CompanionShape.ALPHABET.map { custom.getValue(it) }.joinToString("")
        val keyboard = k("a:merged14#mine")
        val requests =
            CompanionRequests(
                UserKeyboards.Stored(listOf(keyboard, k("a:merged18#merged18")), setOf("a")),
                folds = mapOf(keyboard.id to targets),
            )
        val shapes = requests.shapes("a")
        assertEquals(
            setOf(
                CompanionShape(KeyboardLayoutKind.MERGED14, fold),
                CompanionShape.builtin(KeyboardLayoutKind.MERGED18),
            ),
            shapes,
        )
        assertEquals("merged14:" + fold.id, shapes.first().wire)
        // The request survives the mirror, and only that fold's companion counts as built.
        assertEquals(requests, CompanionRequests.decode(requests.encode()))
        val bundled = KeyboardResourceSet.EncodingAdapter("a", "x14", KeyboardLayoutKind.MERGED14)
        val own = bundled.copy(schemaID = "x14own", fold = fold.id)
        assertEquals(
            setOf(shapes.first()),
            CompanionStatus.missing(mapOf("a" to shapes), listOf(bundled), emptyMap())["a"]!! -
                CompanionShape.builtin(KeyboardLayoutKind.MERGED18),
        )
        assertTrue(CompanionStatus.missing(mapOf("a" to setOf(shapes.first())), listOf(own), emptyMap()).isEmpty())
    }
}
