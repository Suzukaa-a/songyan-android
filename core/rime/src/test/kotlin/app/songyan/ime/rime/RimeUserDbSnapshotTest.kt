package app.songyan.ime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Behaviour of iOS `RimeLearningBackupTests` (the `RimeLearningFormat` checks). */
class RimeUserDbSnapshotTest {
    private fun snapshot(
        name: String = "proof",
        user: String = "source",
        body: String = "x \tA\tc=3 d=100 t=0\n",
    ) = RimeLearningSnapshot(
        name,
        (
            "# Rime user dictionary\n#@/db_name\t$name\n#@/db_type\tuserdb\n#@/rime_version\t1.17.0\n" +
                "#@/tick\t200\n#@/user_id\t$user\n" + body
        ).toByteArray(Charsets.UTF_8),
    )

    private fun assertRefused(
        kind: RimeLearningException.Kind,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("expected $kind")
        } catch (e: RimeLearningException) {
            assertEquals(kind, e.kind)
        }
    }

    @Test
    fun parseReadsIdentityAndEntryCount() {
        val parsed = RimeUserDbSnapshot.parse(snapshot(body = "x \tA\tc=3 d=1 t=0\ny \tB\tc=1 d=1 t=0\n"))
        assertEquals(RimeUserDbDocument("proof", "1.17.0", "source", 2), parsed)
        assertEquals(0, RimeUserDbSnapshot.parse(snapshot(body = "")).entryCount)
    }

    @Test
    fun wrongDictionaryOrMetadataIsRefused() {
        val text = String(snapshot().data, Charsets.UTF_8)
        for ((from, to) in listOf(
            "#@/db_name\tproof" to "#@/db_name\tother",
            "#@/db_type\tuserdb" to "#@/db_type\ttabledb",
            "#@/user_id\tsource\n" to "",
        )) {
            assertRefused(RimeLearningException.Kind.IDENTITY_MISMATCH) {
                RimeUserDbSnapshot.parse(RimeLearningSnapshot("proof", text.replace(from, to).toByteArray()))
            }
        }
        assertRefused(RimeLearningException.Kind.INVALID_SNAPSHOT) {
            RimeUserDbSnapshot.parse(RimeLearningSnapshot("proof", "junk\n".toByteArray()))
        }
        assertRefused(RimeLearningException.Kind.INVALID_SNAPSHOT) {
            RimeUserDbSnapshot.parse(
                RimeLearningSnapshot("proof", text.replace("#@/tick\t200", "#@/tick\t").toByteArray()),
            )
        }
        assertRefused(RimeLearningException.Kind.INVALID_SNAPSHOT) {
            RimeUserDbSnapshot.parse(RimeLearningSnapshot("proof", byteArrayOf(0xff.toByte(), 0xfe.toByte())))
        }
    }

    @Test
    fun sizeAndCountCaps() {
        assertRefused(RimeLearningException.Kind.INVALID_SNAPSHOT) {
            RimeUserDbSnapshot.parse(
                RimeLearningSnapshot("proof", ByteArray(RimeUserDbSnapshot.MAXIMUM_BYTES + 1) { 65 }),
            )
        }
        assertRefused(RimeLearningException.Kind.INVALID_SNAPSHOT) { RimeUserDbSnapshot.parseBatch(emptyList()) }
        assertRefused(RimeLearningException.Kind.INVALID_SNAPSHOT) {
            RimeUserDbSnapshot.parseBatch((0 until 33).map { snapshot("p$it") })
        }
        val body = "x \t" + "a".repeat(3 * 1024 * 1024) + "\tc=1 d=1 t=0\n"
        assertRefused(RimeLearningException.Kind.INVALID_SNAPSHOT) {
            RimeUserDbSnapshot.parseBatch(listOf("a", "b", "c").map { snapshot(it, body = body) })
        }
    }

    @Test
    fun namesCannotEscapeOrCollide() {
        for (name in listOf("", ".temp", "../x", "x/y", "x\\y", "x.userdb", " x")) {
            assertRefused(RimeLearningException.Kind.INVALID_SNAPSHOT) { RimeUserDbSnapshot.validateName(name) }
        }
        assertRefused(RimeLearningException.Kind.INVALID_SNAPSHOT) {
            RimeUserDbSnapshot.parseBatch(listOf(snapshot("proof"), snapshot("PROOF")))
        }
    }
}
