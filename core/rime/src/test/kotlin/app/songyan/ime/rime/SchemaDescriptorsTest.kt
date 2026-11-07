package app.songyan.ime.rime

import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The engine's schema list carries the declared speller alphabet: the fact the layout rules read to
 * place an id the language table cannot name (an alias with the zhuyin key map).
 */
class SchemaDescriptorsTest {
    @Test
    fun everyListedSchemaGetsItsDeclaredAlphabet() =
        runTest {
            val listed =
                listOf(
                    KeyboardSchemaDescriptor("dachen", "大字注音"),
                    KeyboardSchemaDescriptor("songyan_quanpin", "内置全拼"),
                    KeyboardSchemaDescriptor("silent", "无字母表"),
                )
            val reads = mutableListOf<String>()
            val filled =
                SchemaDescriptors.withDeclaredAlphabets(listed) { id ->
                    reads += id
                    when (id) {
                        "dachen" -> "1qaz2wsx-;,./"
                        "songyan_quanpin" -> "abcdefghijklmnopqrstuvwxyz"
                        else -> null
                    }
                }
            assertEquals(listOf("dachen", "songyan_quanpin", "silent"), reads)
            assertEquals(
                listOf(
                    KeyboardSchemaDescriptor("dachen", "大字注音", "1qaz2wsx-;,./"),
                    KeyboardSchemaDescriptor("songyan_quanpin", "内置全拼", "abcdefghijklmnopqrstuvwxyz"),
                    KeyboardSchemaDescriptor("silent", "无字母表", ""),
                ),
                filled,
            )
        }
}
