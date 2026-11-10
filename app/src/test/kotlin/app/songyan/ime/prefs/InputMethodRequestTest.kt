package app.songyan.ime.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputMethodRequestTest {
    private val ids = listOf("rime_ice", "double")

    private fun choose(
        request: InputMethodRequest?,
        sourceId: String = "scheme-1",
    ) = SchemaReconciliation.choose(
        current = "songyan_flypy",
        previousNamespace = "pinyin",
        namespace = "scheme-1",
        primarySchemaId = "rime_ice",
        schemaIds = ids,
        sourceId = sourceId,
        request = request,
    )

    @Test
    fun aRequestForTheActivatedSourceChoosesItsInputMethod() {
        assertEquals("double", choose(InputMethodRequest("scheme-1", "double")))
    }

    @Test
    fun aRequestForAnotherSourceOrAMissingInputMethodIsIgnored() {
        assertEquals("rime_ice", choose(InputMethodRequest("scheme-2", "double")))
        assertEquals("rime_ice", choose(InputMethodRequest("scheme-1", "gone")))
        assertEquals("rime_ice", choose(null))
    }

    @Test
    fun theRequestRoundTripsAndRejectsMalformedValues() {
        val request = InputMethodRequest("scheme-1", "double")
        assertEquals(request, InputMethodRequest.decode(request.encode()))
        assertNull(InputMethodRequest.decode(null))
        assertNull(InputMethodRequest.decode("scheme-1"))
        assertNull(InputMethodRequest.decode("\tdouble"))
    }
}
