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

    @Test
    fun patchOperatorsAndListSelectorsDoNotHideAFileKey() {
        for (yaml in listOf(
            "patch:\n  translator/user_dict/=: /data/data/x/files/rime/config/edit/leak\n",
            "patch:\n  translator/user_dict/+: ../../leak\n",
            "patch:\n  translator/dictionary/+: /abs\n",
            "patch:\n  schema_list/@0/schema: ../../x\n",
            "patch:\n  schema_list/@next/schema: /abs\n",
            "patch:\n  schema_list/@before 0/schema: ../x\n",
            "patch:\n  schema_list/@last/schema/=: ../x\n",
            "patch:\n  schema/dependencies/@after 1: ../x\n",
            "patch:\n  translator:\n    user_dict:\n      __append: ../x\n",
            "patch:\n  translator:\n    db:\n      __merge: /abs\n",
            "patch:\n  predictor/db/=: ../../x\n",
        )) {
            assertNotNull(yaml, unsafe(yaml))
        }
        // Appending a list of plain names is an ordinary patch.
        assertNull(unsafe("patch:\n  schema_list/+:\n    - schema: extra\n  translator/packs/+: [a, b]\n"))
        assertNull(unsafe("patch:\n  translator/user_dict/=: my_words\n"))
    }

    @Test
    fun schemaListEntriesDependenciesAndPacksAreChecked() {
        assertNotNull(unsafe("schema_list:\n  - schema: ../../edit/live\n"))
        assertNotNull(unsafe("schema:\n  dependencies:\n    - ../x\n"))
        assertNotNull(unsafe("translator:\n  packs:\n    - /abs/pack\n"))
        assertNull(
            unsafe(
                "schema_list:\n  - schema: rime_ice\nschema:\n  schema_id: rime_ice\n  dependencies: [luna_pinyin]\n",
            ),
        )
    }

    @Test
    fun explicitScalarTagsCannotHideAValueFromTheChecks() {
        val tagged = "translator:\n  dictionary: !!null ../../x\n  user_dict: !!bool /abs\n"
        // The typed reader turns these into null / true / a number: nothing to check.
        assertNull(UserConfigSafety.unsafeReference(BoundedYaml.load(tagged)))
        // The text reader keeps what yaml-cpp (librime) reads.
        assertNotNull(UserConfigSafety.unsafeReference(BoundedYaml.loadAsText(tagged)))
        assertNotNull(UserConfigSafety.unsafeReference(BoundedYaml.loadAsText("!!null dictionary: ../x\n")))
        assertEquals(
            listOf("lua_filter@x"),
            UserConfigSafety.luaComponents(BoundedYaml.loadAsText("engine:\n  filters:\n    - !!null lua_filter@x\n")),
        )
    }

    @Test
    fun aBareLuaComponentNameCountsToo() {
        val root =
            BoundedYaml.loadAsText(
                "engine:\n  filters:\n    - lua_filter\n    - lua_filters_not\n    - lua_translator@t\n",
            )
        assertEquals(listOf("lua_filter", "lua_translator@t"), UserConfigSafety.luaComponents(root))
    }

    @Test
    fun aRepeatedKeyIsKeptSoTheHiddenPairIsChecked() {
        val doubled =
            "__patch:\n  translator/user_dict: /abs/leak\n  engine/filters/@next: lua_filter@*x\n__patch: {}\n"
        val root = BoundedYaml.loadAsText(doubled) as Map<*, *>
        assertEquals(listOf("__patch", "__patch/="), root.keys.toList())
        assertNotNull(UserConfigSafety.unsafeReference(root))
        assertEquals(
            listOf("lua_filter@*x"),
            UserConfigSafety.luaComponents(BoundedYaml.loadAsText("a:\n  f: lua_filter@*x\na: {}\n")),
        )
        assertNotNull(
            "a hidden include",
            UserConfigSafety.unsafeReference(BoundedYaml.loadAsText("__include: /abs/edit/evil:/\n__include: ok\n")),
        )
        // Schemas that repeat a key on purpose are still readable.
        assertNull(
            UserConfigSafety.unsafeReference(BoundedYaml.loadAsText("menu:\n  page_size: 5\nmenu:\n  page_size: 7\n")),
        )
    }

    @Test
    fun stringsAreNeverBuiltFromAppendedPieces() {
        for (yaml in listOf(
            "patch:\n  engine/filters/@next: lu\n  engine/filters/@last/+: \"a_filter@*x\"\n",
            "patch:\n  menu/foo/+: text\n",
            "patch:\n  a:\n    __append: text\n",
            "patch:\n  a:\n    __merge: text\n",
        )) {
            assertNotNull(yaml, unsafe(yaml))
        }
        assertNull(unsafe("patch:\n  engine/filters/+:\n    - uniquifier\n  a:\n    __append: [x]\n"))
    }

    @Test
    fun aPunctuationKeyNamedPlusIsNotTheAppendOperator() {
        // rime-ice's compiled default.yaml maps the + symbol; librime only reads a key ending in `/+` as append.
        assertNull(unsafe("punctuator:\n  half_shape:\n    \"+\": \"+\"\n    \"/\": \"/\"\n"))
        assertNotNull(unsafe("patch:\n  \"punctuator/half_shape/+\": \"+\"\n"))
    }
}
