package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Ported from RuntimeImportRegressionTests: dependency closure and path safety of imported sources. */
class RuntimeImportRegressionTest {
    private fun missing(files: Map<String, String>): List<String> {
        val bytes = files.mapValues { it.value.toByteArray() }
        return SchemeSourceDependencies(bytes.keys) { bytes[it] }.missing()
    }

    @Test
    fun librimeReferencesUseParsedNodesAndTheExternalQualifier() {
        val files =
            mutableMapOf(
                "sample.schema.yaml" to
                    (
                        "schema: {schema_id: sample}\n" +
                            "algebra_common: [erase/^xx\$/]\n" +
                            "algebra_rime_ice: {__include: algebra_common}\n" +
                            "speller: {__include: 'algebra_rime_ice'}\n" +
                            "__patch: [local_patch, ':/local_patch', 'external.yaml:/patch', {menu/page_size: 7}]\n" +
                            "local_patch: {__include: '/algebra_common'}\n" +
                            "optional: {__include: 'absent:/node?'}\n" +
                            "translator: {dictionary: null}\n" +
                            "literal: |\n  __include: not_a_dependency:/node\n"
                    ),
                "external.yaml" to "patch: {__include: 'nested/config:/patch'}\n",
                "nested/config.yaml" to "patch: {menu/page_size: 5}\n",
                "others/example.dict.yaml" to "not a deployed dictionary\n",
                "others/example.schema.yaml" to "__include: missing_example:/node\n",
            )
        assertEquals(emptyList<String>(), missing(files))
        assertEquals(
            emptyList<String>(),
            missing(mapOf("sample.dict.yaml" to "---\r\nname: sample\r\n... # header ends\r\n词\tci\t100\r\n")),
        )
        files.remove("nested/config.yaml")
        val absent = missing(files)
        assertTrue(absent.toString(), "nested/config.yaml" in absent)
        assertFalse(absent.toString(), absent.any { it.contains("algebra_common.yaml") })
        try {
            missing(mapOf("s.schema.yaml" to "__patch: ['../escape:/node']"))
            fail("a traversing reference must be rejected")
        } catch (_: SchemeSourceDependencies.Invalid) {
            // Expected.
        }
    }

    @Test
    fun anOpenCcConfigNeedsItsDataFilesFromTheSameSourcePool() {
        val schema = "schema: {schema_id: sample}\nsimplifier: {opencc_config: s2t.json}\n"
        val chain = "{\"conversion_chain\":[{\"dict\":{\"type\":\"text\",\"file\":\"private.txt\"}}]}"
        val source = mapOf("sample.schema.yaml" to schema)
        assertEquals(listOf("opencc/s2t.json"), missing(source))
        val complete =
            source +
                mapOf(
                    "opencc/s2t.json" to
                        "{\"conversion_chain\":[{\"dict\":{\"type\":\"ocd2\",\"file\":\"STPhrases.ocd2\"}}]}",
                )
        assertEquals(listOf("opencc/STPhrases.ocd2"), missing(complete))
        // A package's own s2t.json is checked against the package, not against shared data.
        assertEquals(listOf("opencc/private.txt"), missing(source + mapOf("opencc/s2t.json" to chain)))
        assertEquals(
            emptyList<String>(),
            missing(source + mapOf("opencc/s2t.json" to chain, "opencc/private.txt" to "简\t簡\n")),
        )
    }

    @Test
    fun unicodeNamesAreSafeAndTraversalBoundariesAreNot() {
        for (path in listOf("others/asserts/基础-Emoji_compressed.webp", "说明/输入 方案😀.txt")) {
            assertTrue(path, SchemeSourceCheck.safe(path))
        }
        for (path in listOf("../escape", "/absolute", "a/../../b", "C:/drive", "a\\b", "a//b", "a/./b", "a\u0000b")) {
            assertFalse(path, SchemeSourceCheck.safe(path))
        }
    }
}
