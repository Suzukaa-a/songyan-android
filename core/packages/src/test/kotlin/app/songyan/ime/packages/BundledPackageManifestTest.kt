package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledPackageManifestTest {
    private val manifest =
        """
        # Manifest of the built-in schemes
        formatVersion: 1
        id: songyan-builtin
        version: 1.0.0
        kind: schema
        name: 内置输入方案
        minimumAppVersion: 0.1.0
        requires:
          engineFeatures:
            - script_translator
        entrypoints:
          schemas:
            - songyan_quanpin
            - songyan_flypy
        dictionaries:
          - name: songyan_basic
            file: songyan_basic.dict.yaml
            sharedUserDict: songyan_shared
            note: bundled dictionary

        """.trimIndent()

    @Test
    fun parsesTheManifestFieldsTheIOSLoaderReads() {
        val parsed = BundledPackageManifest.parse(manifest.toByteArray(Charsets.UTF_8))
        assertEquals(1, parsed.formatVersion)
        assertEquals("songyan-builtin", parsed.id)
        assertEquals("1.0.0", parsed.version)
        assertEquals("schema", parsed.kind)
        assertEquals("内置输入方案", parsed.name)
        assertEquals("0.1.0", parsed.minimumAppVersion)
        assertEquals(listOf("script_translator"), parsed.requiresEngineFeatures)
        assertEquals(listOf("songyan_quanpin", "songyan_flypy"), parsed.entrypointSchemas)
        assertEquals(1, parsed.dictionaries.size)
        assertEquals("songyan_basic", parsed.dictionaries[0].name)
        assertEquals("songyan_basic.dict.yaml", parsed.dictionaries[0].file)
        assertEquals("songyan_shared", parsed.dictionaries[0].sharedUserDict)
    }

    @Test
    fun refusesManifestsWithoutIdentityOrEntrySchemas() {
        for (
        text in
        listOf(
            "name: x\nentrypoints:\n  schemas:\n    - a\n",
            "id: x\nentrypoints:\n  schemas:\n    - a\n",
            "id: x\nname: y\n",
            "id: x\nname: y\nentrypoints:\n  schemas: []\n",
            "- not a mapping\n",
        )
        ) {
            assertThrows("manifest: " + text, RecipeInstallError.InvalidManifest::class.java) {
                BundledPackageManifest.parse(text.toByteArray(Charsets.UTF_8))
            }
        }
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            BundledPackageManifest.parse(byteArrayOf(0xC3.toByte()))
        }
    }

    @Test
    fun refusesUnparseableYamlAndStructurallyBrokenDictionaries() {
        // A YAML syntax error must be a RecipeInstallError, not a snakeyaml exception.
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            BundledPackageManifest.parse("id: x\nname: y\nentrypoints: [unterminated\n".toByteArray())
        }
        val base = "id: x\nname: y\nentrypoints:\n  schemas:\n    - a\n"
        // A mapping instead of a list, or an entry missing its name/file, silently installed a
        // package without that dictionary; both are refused now.
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            BundledPackageManifest.parse((base + "dictionaries:\n  name: d\n  file: d.yaml\n").toByteArray())
        }
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            BundledPackageManifest.parse((base + "dictionaries:\n  - name: d\n").toByteArray())
        }
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            BundledPackageManifest.parse((base + "dictionaries:\n  - file: d.yaml\n").toByteArray())
        }
    }

    @Test
    fun selectInstallFilesKeepsOnlyTheEntrySchemas() {
        val parsed = BundledPackageManifest.parse(manifest.toByteArray(Charsets.UTF_8))
        val files =
            mapOf(
                "manifest.yaml" to manifest.toByteArray(Charsets.UTF_8),
                "default.yaml" to
                    (
                        "schema_list:\n  - schema: songyan_quanpin\n" +
                            "  - schema: keyboard_x_14\n"
                    ).toByteArray(),
                "songyan_quanpin.schema.yaml" to "schema:\n  schema_id: songyan_quanpin\n".toByteArray(),
                "songyan_flypy.schema.yaml" to "schema:\n  schema_id: songyan_flypy\n".toByteArray(),
                "keyboard_x_14.schema.yaml" to "schema:\n  schema_id: keyboard_x_14\n".toByteArray(),
                "songyan_basic.dict.yaml" to "name: songyan_basic\n".toByteArray(),
                "README.md" to "not installed\n".toByteArray(),
            )
        val selected = BundledPackageManifest.selectInstallFiles(parsed, files)
        assertFalse(selected.containsKey("manifest.yaml"))
        assertFalse(
            "non-entry companion is left to the keyboard bundle",
            selected.containsKey("keyboard_x_14.schema.yaml"),
        )
        assertFalse(selected.containsKey("README.md"))
        assertTrue(selected.containsKey("songyan_quanpin.schema.yaml"))
        assertTrue(selected.containsKey("songyan_flypy.schema.yaml"))
        assertEquals(
            "schema_list:\n  - schema: songyan_quanpin\n",
            String(selected.getValue("default.yaml"), Charsets.UTF_8),
        )
        // The manifest read is separate from the installed tree.
        assertNull(selected["manifest.yaml"])
    }

    @Test
    fun selectInstallFilesRequiresDefaultYaml() {
        val parsed = BundledPackageManifest.parse(manifest.toByteArray(Charsets.UTF_8))
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            BundledPackageManifest.selectInstallFiles(parsed, mapOf("songyan_quanpin.schema.yaml" to ByteArray(0)))
        }
    }
}
