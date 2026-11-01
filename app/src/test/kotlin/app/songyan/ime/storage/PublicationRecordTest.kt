package app.songyan.ime.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicationRecordTest {
    private val record =
        PublicationRecord(
            version = PublicationRecord.CURRENT_VERSION,
            generation = "1712345678901-songyan-builtin-ab12",
            sourceId = "songyan-builtin",
            schemaId = "songyan_quanpin",
            revision = "0.1.0/1",
            createdAtEpochMs = 1712345678901L,
        )

    @Test
    fun jsonRoundTrip() {
        assertEquals(record, PublicationRecord.parse(record.toJson()))
    }

    @Test
    fun escapesSpecialCharacters() {
        val tricky = record.copy(revision = "a\"b\\c\nd\tü", sourceId = "src-\u0001")
        val parsed = PublicationRecord.parse(tricky.toJson())
        assertEquals(tricky, parsed)
    }

    @Test
    fun rejectsMissingOrCorrupt() {
        assertNull(PublicationRecord.parse(null))
        assertNull(PublicationRecord.parse(""))
        assertNull(PublicationRecord.parse("{"))
        assertNull(PublicationRecord.parse("{\"version\":\"two\"}"))
        assertNull(PublicationRecord.parse("{\"version\":2}"))
        assertNull(PublicationRecord.parse("{\"version\":1,\"generation\":\"g\"}"))
    }

    @Test
    fun rejectsUnknownVersion() {
        val json = record.toJson().replace("\"version\":2", "\"version\":3")
        assertNull(PublicationRecord.parse(json))
    }

    @Test
    fun carriesTheBuildStampAndInputMethods() {
        val stamped =
            record.copy(
                sequence = 7,
                schemaIds = listOf("a", "b"),
                stamp = BuildStamp("1.0/1/2", "1.17.0", "2"),
            )
        assertEquals(stamped, PublicationRecord.parse(stamped.toJson()))
    }

    @Test
    fun aRecordOfAnEarlierVersionReadsWithoutAStamp() {
        val legacy =
            "{\"version\":1,\"generation\":\"g\",\"sourceId\":\"songyan-builtin\",\"schemaId\":\"songyan_quanpin\"," +
                "\"revision\":\"1\",\"createdAt\":5}"
        val parsed = PublicationRecord.parse(legacy)!!
        assertNull("no stamp means out of date", parsed.stamp)
        assertEquals(emptyList<String>(), parsed.schemaIds)
    }
}
