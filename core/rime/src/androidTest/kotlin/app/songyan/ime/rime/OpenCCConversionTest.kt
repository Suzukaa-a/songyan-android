package app.songyan.ime.rime

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OpenCCConversionTest {
    companion object {
        private lateinit var data: File

        @BeforeClass
        @JvmStatic
        fun copyShippedDictionaries() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            data = File(context.cacheDir, "opencc-conversion-" + System.nanoTime())
            data.mkdirs()
            EngineTestSupport.copyAssetTree(context.assets, "rime/opencc", data)
        }
    }

    private fun converter() = OpenCCConversion { data }

    @Test
    fun shippedTaiwanPhraseConversionUsesTheActualDictionaries() =
        runBlocking {
            assertEquals("軟體", converter().convert("软件", "s2twp"))
            assertEquals("繁体", converter().convert("繁體", "t2s"))
        }

    @Test
    fun supplementaryCharactersAndNulPreserveAllConvertedSegments() =
        runBlocking {
            assertEquals("軟體🙂\u0000軟體𠀀", converter().convert("软件🙂\u0000软件𠀀", "s2twp"))
        }

    @Test
    fun emptyTextAndWidthStayUnchanged() =
        runBlocking {
            assertEquals("", converter().convert("", "s2twp"))
            assertEquals("ＡＢＣ１", converter().convert("ＡＢＣ１", "s2twp"))
        }

    @Test
    fun resolvingAndConvertingFromMainDoesNotRunThere() =
        runBlocking {
            var directoryReadOnMain = true
            val conversion =
                OpenCCConversion {
                    directoryReadOnMain = Looper.myLooper() == Looper.getMainLooper()
                    data
                }
            val result = withContext(Dispatchers.Main) { conversion.convert("软件", "s2twp") }
            assertEquals("軟體", result)
            assertFalse(directoryReadOnMain)
        }

    @Test
    fun nativeConfigurationFailureReturnsStatusAndDiagnostic() {
        assertTrue(RimeNative.ensureLoaded())
        val result = arrayOfNulls<String>(2)
        val status = RimeNative.nativeOpenccConvert(File(data, "missing.json").absolutePath, "软件", result)
        assertEquals(2, status)
        assertEquals(null, result[0])
        assertNotNull(result[1])
        assertTrue(result[1]!!.isNotBlank())
    }

    @Test
    fun independentConversionsCanRunConcurrentlyWithoutStartingRime() =
        runBlocking {
            val results =
                coroutineScope {
                    listOf("软件", "软件🙂")
                        .map { input ->
                            async(Dispatchers.Default) { converter().convert(input, "s2twp") }
                        }.awaitAll()
                }
            assertEquals(listOf("軟體", "軟體🙂"), results)
        }
}
