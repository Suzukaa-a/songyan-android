package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a user's configuration may name: nothing that leaves the data folders. */
class UserConfigSafetyTest {
    private fun unsafe(yaml: String): String? = UserConfigSafety.unsafeReference(BoundedYaml.load(yaml))

    @Test
    fun pathsFollowTheImportRules() {
        for (path in listOf("lua/date.lua", "a b/c.yaml", "opencc/emoji.json")) {
            assertTrue(path, UserConfigSafety.safePath(path))
        }
        for (path in listOf(
            "",
            "/etc/passwd",
            "../x",
            "a/../x",
            "a//b",
            "a\\b",
            "c:/x",
            "a\u0000b",
            "a\nb",
            "x/".repeat(40) + "y",
            "n".repeat(300),
        )) {
            assertFalse(path, UserConfigSafety.safePath(path))
        }
    }

    @Test
    fun ordinaryPatchesAndSchemasHaveNoUnsafeReference() {
        assertNull(
            unsafe(
                """
                patch:
                  menu/page_size: 7
                  translator/dictionary: rime_ice
                  grammar/language: wanxiang-lts-zh-hans
                  "speller/algebra/+": [ "derive/^([a-z]+)$/$1x/" ]
                  __include: default:/menu
                """.trimIndent(),
            ),
        )
        assertNull(unsafe("import_tables:\n  - cn_dicts/base\n  - cn_dicts/ext\nuse_preset_vocabulary: true\n"))
        assertNull(unsafe("schema:\n  dependencies:\n    - luna_pinyin\ntranslator:\n  db: custom\n"))
    }

    @Test
    fun namesThatLeaveTheDataFoldersAreFound() {
        for (yaml in listOf(
            "translator:\n  dictionary: ../../../etc/passwd\n",
            "patch:\n  translator/dictionary: /data/data/x/secret\n",
            "__include: ../../x:/menu\n",
            "__patch:\n  - ../x:/patch\n",
            "__patch: [\"/abs:/x\"]\n",
            "import_preset: \"..\\\\x\"\n",
            "import_tables:\n  - ../x\n",
            "speller:\n  prism: ../prism\n",
            "translator:\n  user_dict: /sdcard/x\n",
            "translator:\n  vocabulary: ../../essay\n",
            "patch:\n  opencc_config: ../x.json\n",
            "predictor:\n  db: ../../db\n",
            "grammar:\n  language: ../../model\n",
            "patch:\n  grammar/language: c:/x\n",
            "key: 1\nnested:\n  list:\n    - translator:\n        dictionary: a/../../b\n",
        )) {
            assertNotNull(yaml, unsafe(yaml))
        }
        // A reference to its own file has no file part, and a plain name is fine.
        assertNull(unsafe("__include: menu\n"))
    }

    @Test
    fun deepFlowNestingIsRefusedAndQuotesAndCommentsAreNotCounted() {
        assertTrue(UserConfigSafety.tooDeep("a: " + "[".repeat(100) + "\n"))
        assertTrue(UserConfigSafety.tooDeep("a: " + "{b: ".repeat(100) + "1" + "}".repeat(100) + "\n"))
        assertFalse(UserConfigSafety.tooDeep("a: [[1, 2], {b: [3]}]\n"))
        assertFalse(UserConfigSafety.tooDeep("a: \"" + "[".repeat(200) + "\"\n"))
        assertFalse(UserConfigSafety.tooDeep("a: 1 # " + "[".repeat(200) + "\n"))
    }

    @Test
    fun luaComponentEntriesAreFoundAnywhere() {
        val root =
            BoundedYaml.load(
                """
                engine:
                  filters:
                    - lua_filter@*my.filter
                    - uniquifier
                  processors:
                    - lua_processor@date_proc
                patch:
                  "engine/translators/+": [lua_translator@*t]
                note: not a lua_filter@x entry in prose
                """.trimIndent(),
            )

        assertEquals(
            listOf("lua_filter@*my.filter", "lua_processor@date_proc", "lua_translator@*t"),
            UserConfigSafety.luaComponents(root),
        )
    }
}
