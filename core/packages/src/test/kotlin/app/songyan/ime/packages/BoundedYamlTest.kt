package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedYamlTest {
    @Test
    fun loadsMappingsSequencesAndScalarsWithTheirTypes() {
        val loaded =
            BoundedYaml
                .load(
                    "schema:\n  schema_id: x\nnumbers:\n  - 12\n  - true\n  - null\n  - 1.5\n",
                ).asStringMap()
        assertNotNull(loaded)
        assertEquals("x", loaded!!.get("schema").asStringMap()?.get("schema_id"))
        val numbers = loaded["numbers"] as List<*>
        assertEquals(12, numbers[0])
        assertEquals(true, numbers[1])
        assertNull(numbers[2])
        assertEquals(1.5, numbers[3])
        // An unquoted numeric alphabet stays a number, which is what eligibility reads literally.
        assertEquals(23456789, BoundedYaml.load("alphabet: 23456789\n").asStringMap()!!["alphabet"])
        assertNull(BoundedYaml.load(""))
    }

    @Test
    fun resolvesAliasesWithinBudget() {
        val loaded =
            BoundedYaml
                .load("base: &b\n  k: v\nderived:\n  <<: *b\n  m: n\n")
                .asStringMap()!!
        // Merge keys are not expanded: yaml-cpp and the Rime config compiler leave the key as
        // written (Yams merges), and validation must see the file the way the engine does.
        assertEquals(
            "v",
            loaded["derived"]
                .asStringMap()
                ?.get("<<")
                .asStringMap()
                ?.get("k"),
        )
        assertEquals("n", loaded["derived"].asStringMap()?.get("m"))
    }

    /**
     * librime 1.17 (`ConvertFromYaml`, config_data.cc) iterates yaml-cpp 0.8.0's map, which keeps
     * both pairs of a repeated key (verified by loading each document below: size 2), and stores
     * them with `ConfigMap::Set` (`map_[key] = element`). The later pair replaces the earlier
     * one whole: there is no deep merge, and a later null or a different node type replaces too.
     * Yams on iOS resolves duplicates the same way (`uniquingKeysWith: { _, second in second }`).
     */
    @Test
    fun duplicateKeysTakeTheLaterValueWholesaleLikeLibrime() {
        assertEquals("b", BoundedYaml.load("k: a\nk: b\n").asStringMap()!!["k"])
        // Quoting does not make a different key.
        assertEquals("b", BoundedYaml.load("k: a\n'k': b\n").asStringMap()!!["k"])
        assertEquals("b", BoundedYaml.load("{k: a, k: b}\n").asStringMap()!!["k"])
        // No deep merge: the earlier mapping's keys are gone.
        val replaced = BoundedYaml.load("m:\n  x: 1\nm:\n  y: 2\n").asStringMap()!!["m"].asStringMap()!!
        assertEquals(mapOf<String, Any?>("y" to 2), replaced)
        // A later value of another type replaces, and so does a later null.
        assertEquals(listOf("z"), BoundedYaml.load("k: 1\nk:\n  - z\n").asStringMap()!!["k"])
        assertNull(BoundedYaml.load("k: a\nk:\n").asStringMap()!!.getValue("k"))
        // Nested duplicates follow the same rule.
        val nested = BoundedYaml.load("a:\n  k: 1\n  k: 2\n").asStringMap()!!["a"].asStringMap()!!
        assertEquals(2, nested["k"])
    }

    @Test
    fun rejectsAnAliasBomb() {
        val bomb = aliasBomb(levels = 8, width = 10)
        try {
            BoundedYaml.load(bomb)
            fail("an alias bomb must be rejected")
        } catch (error: BoundedYaml.LimitExceeded) {
            assertTrue(error.reason.isNotEmpty())
        }
    }

    @Test
    fun rejectsAnAliasUsedAsAMappingKey() {
        try {
            BoundedYaml.load("a: &a {x: 1}\n*a: value\n")
            fail("an alias key must be rejected")
        } catch (error: BoundedYaml.LimitExceeded) {
            assertTrue(error.reason.isNotEmpty())
        }
    }

    @Test
    fun aStarInsideAScalarIsNotAnAlias() {
        val loaded = BoundedYaml.load("key: \"a*b\"\nother: plain *star\n").asStringMap()!!
        assertEquals("a*b", loaded["key"])
        assertEquals("plain *star", loaded["other"])
    }

    @Test
    fun composeReturnsNodesAndRejectsInvalidText() {
        assertNotNull(BoundedYaml.compose("a: 1\n"))
        assertNull(BoundedYaml.compose(""))
        try {
            BoundedYaml.compose("key: [unterminated\n")
            fail("invalid YAML must fail")
        } catch (_: Exception) {
            // Expected: the composer reports the syntax error.
        }
    }

    @Test
    fun decodesUtf8Strictly() {
        assertEquals("ü", decodeUtf8("ü".toByteArray(Charsets.UTF_8)))
        assertNull(decodeUtf8(byteArrayOf(0xC3.toByte())))
        assertTrue(decodeUtf8Lenient(byteArrayOf(0xC3.toByte())).isNotEmpty())
    }

    private fun aliasBomb(
        levels: Int,
        width: Int,
    ): String {
        val text = StringBuilder()
        text.append("a0: &a0 [")
        for (index in 0 until width) {
            if (index > 0) text.append(", ")
            text.append("x")
        }
        text.append("]\n")
        for (level in 1 until levels) {
            text.append("a" + level + ": &a" + level + " [")
            for (index in 0 until width) {
                if (index > 0) text.append(", ")
                text.append("*a" + (level - 1))
            }
            text.append("]\n")
        }
        return text.toString()
    }

    @Test
    fun aMergedKeyStaysInvisibleAtTheParent() {
        val loaded = BoundedYaml.load("base: &b\n  schema_list: [x]\nroot:\n  <<: *b\n").asStringMap()!!
        assertNull(loaded["root"].asStringMap()!!["schema_list"])
    }

    @Test
    fun unknownTagsReadAsUntaggedValues() {
        val loaded =
            BoundedYaml
                .load(
                    "a: !custom value\nb: !<tag:example.org,2026:t> {k: 1}\nc: !seq [x, 2]\nd: !!binary aGk=\ne: !x 5\n",
                ).asStringMap()!!
        assertEquals("value", loaded["a"])
        assertEquals(mapOf("k" to 1), loaded["b"])
        assertEquals(listOf("x", 2), loaded["c"])
        assertEquals("aGk=", loaded["d"])
        assertEquals(5, loaded["e"])
    }

    @Test
    fun mergesAreCountedAgainstTheExpansionBudget() {
        // Every level merges the previous one twice; the merge alias tokens are what expands it.
        val bomb =
            buildString {
                append("l0: &l0 {a: 1}\n")
                for (i in 1..30) append("l$i: &l$i {<<: [*l${i - 1}, *l${i - 1}]}\n")
            }
        try {
            BoundedYaml.load(bomb)
            fail("a merge bomb must be rejected")
        } catch (error: BoundedYaml.LimitExceeded) {
            assertTrue(error.reason.isNotEmpty())
        }
        try {
            YamsYaml.load(bomb)
            fail("a merge bomb must be rejected on the Yams path too")
        } catch (error: BoundedYaml.LimitExceeded) {
            assertTrue(error.reason.isNotEmpty())
        }
    }
}
