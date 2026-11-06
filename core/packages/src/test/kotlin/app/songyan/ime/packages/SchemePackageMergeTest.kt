package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A catalogue entry assembled from several pinned upstream archives: the main archive is the
 * package, its dependencies land at the same package-relative paths so librime resolves them, every
 * upstream licence survives under its own attribution path, and a runtime file two archives fill
 * differently is refused instead of silently resolving to one upstream's bytes.
 */
class SchemePackageMergeTest {
    private val root: File = Files.createTempDirectory("scheme-merge").toFile().also { it.deleteOnExit() }
    private var outputs = 0

    @Test
    fun everyArchiveContributesItsFilesAtThePackageRoot() {
        val prepared = prepare(listOf(mainArchive(), dependencyArchive()))

        val staged = stagedPaths(prepared.root)
        assertTrue("bopomofo_tw.schema.yaml" in staged)
        assertTrue("terra_pinyin.schema.yaml" in staged)
        assertTrue("terra_pinyin.dict.yaml" in staged)
        assertFalse("no archive wrapper remains", staged.any { it.startsWith("rime-bopomofo-") })
        assertEquals(listOf("bopomofo_tw", "terra_pinyin"), prepared.schemaIds)
    }

    @Test
    fun anAdvertisedInputMethodIsTheOnlyOneOfferedWhileDependenciesStayLoadable() {
        val prepared = prepare(listOf(mainArchive(), dependencyArchive()), advertised = listOf("bopomofo_tw"))

        assertEquals(listOf("bopomofo_tw"), prepared.schemaIds)
        val config = File(prepared.root, "default.yaml").readText()
        assertTrue("the package lists its advertised input method: " + config, config.contains("schema: bopomofo_tw"))
        assertFalse("a dependency schema is not offered", config.contains("schema: terra_pinyin"))
        assertTrue("the dependency stays loadable", File(prepared.root, "terra_pinyin.schema.yaml").isFile)
        assertTrue("its dictionary stays loadable", File(prepared.root, "terra_pinyin.dict.yaml").isFile)
    }

    @Test
    fun anAdvertisedInputMethodTheArchiveDoesNotShipIsRefused() {
        val error =
            assertReason(SchemePackageImportError.Reason.MISSING_SCHEMA) {
                prepare(listOf(mainArchive()), advertised = listOf("cangjie5"))
            }

        val message = error.message.orEmpty()
        assertTrue("the input method is named: " + message, message.startsWith("cangjie5"))
    }

    @Test
    fun theEntryArchiveKeepsItsOwnDefaultConfiguration() {
        val main = mainArchive(extra = mapOf("default.yaml" to defaultConfig("bopomofo_tw")))
        val dependency = dependencyArchive(extra = mapOf("default.yaml" to defaultConfig("terra_pinyin")))

        val prepared = prepare(listOf(main, dependency))

        assertEquals(
            "the dependency's default.yaml never replaces the entry's",
            defaultConfig("bopomofo_tw"),
            File(prepared.root, "default.yaml").readText(),
        )
        assertEquals(listOf("bopomofo_tw"), prepared.schemaIds)
    }

    @Test
    fun filesWithTheSameBytesAreKeptOnce() {
        val prepared = prepare(listOf(mainArchive(), dependencyArchive()))

        val staged = stagedPaths(prepared.root)
        assertEquals("the shared AUTHORS is one file", 1, staged.count { it.endsWith("AUTHORS") })
        assertEquals("the shared dictionary is one file", 1, staged.count { it.endsWith("terra_pinyin.dict.yaml") })
        val duplicated =
            staged.any {
                it.startsWith("source-repositories/") && it.endsWith("terra_pinyin.dict.yaml")
            }
        assertFalse("nothing identical is duplicated under an attribution path", duplicated)
    }

    @Test
    fun documentationAnotherArchiveAlsoShipsIsKeptUnderThatArchivesPath() {
        val prepared = prepare(listOf(mainArchive(), dependencyArchive()))

        val staged = stagedPaths(prepared.root)
        assertEquals("the entry licence keeps the root", "main licence\n", File(prepared.root, "LICENSE").readText())
        assertTrue("the entry readme keeps the root", "# 注音\n" == File(prepared.root, "README.md").readText())
        val licence = staged.any { it.startsWith(DEPENDENCY_PREFIX) && it.endsWith("/LICENSE") }
        val readme = staged.any { it.startsWith(DEPENDENCY_PREFIX) && it.endsWith("/README.md") }
        assertTrue("the dependency's different licence is kept: " + staged, licence)
        assertTrue("the dependency's readme is kept: " + staged, readme)
    }

    @Test
    fun aRuntimeFileTwoArchivesFillDifferentlyIsRefused() {
        val main =
            mainArchive(extra = mapOf("terra_pinyin.dict.yaml" to dictionary("terra_pinyin", "你\tni\t2\n")))

        val error =
            assertReason(SchemePackageImportError.Reason.CONFLICTING_FILE) {
                prepare(listOf(main, dependencyArchive()))
            }

        assertEquals(listOf("terra_pinyin.dict.yaml"), error.paths)
        val message = error.message.orEmpty()
        assertTrue("both archives are named: " + message, message.contains("rime-terra-pinyin-"))
    }

    @Test
    fun aLicenceNameThatCarriesTheLicenceIsStagedWithItsPrefix() {
        val prepared = prepare(listOf(cantoneseArchive()))

        val staged = stagedPaths(prepared.root)
        assertTrue("LICENSE-CC-BY is kept", "LICENSE-CC-BY" in staged)
        assertTrue("LICENSE-ODbL is kept", "LICENSE-ODbL" in staged)
    }

    @Test
    fun anEmptyEntryListIsRefused() {
        assertReason(SchemePackageImportError.Reason.INVALID_ZIP) { prepare(emptyList()) }
    }

    @Test
    fun aDependencyDefaultConfigurationNeverBecomesThePackagesOwn() {
        val dependency = dependencyArchive(extra = mapOf("default.yaml" to defaultConfig("terra_pinyin")))

        val advertised = prepare(listOf(mainArchive(), dependency), advertised = listOf("bopomofo_tw"))
        assertEquals(listOf("bopomofo_tw"), advertised.schemaIds)
        assertEquals(
            "the advertised input methods are generated, not the dependency's list",
            defaultConfig("bopomofo_tw"),
            File(advertised.root, "default.yaml").readText(),
        )

        val plain = prepare(listOf(mainArchive(), dependency))
        assertFalse(
            "a dependency's default.yaml is never staged: " + stagedPaths(plain.root),
            File(plain.root, "default.yaml").exists(),
        )
        assertEquals(listOf("bopomofo_tw", "terra_pinyin"), plain.schemaIds)
    }

    @Test
    fun aPackageFileUnderTheMergeScratchNameKeepsItsBytes() {
        val main = mainArchive(extra = mapOf(".merge/foo.yaml" to "foo: kept\n"))

        val prepared = prepare(listOf(main, dependencyArchive()))

        assertEquals("foo: kept\n", File(prepared.root, ".merge/foo.yaml").readText())
        val directories =
            requireNotNull(prepared.root.parentFile).listFiles().orEmpty().filter { it.isDirectory }
        assertEquals("no merge scratch outlives the import", listOf(prepared.root.name), directories.map { it.name })
    }

    @Test
    fun attributionTextFilesFromDifferentUpstreamsAreKeptApart() {
        val main =
            mainArchive(
                extra = mapOf("LICENSE.txt" to "main licence text\n", "NOTICE.txt" to "main notice\n"),
            )
        val dependency =
            dependencyArchive(
                extra = mapOf("LICENSE.txt" to "dependency licence text\n", "NOTICE.txt" to "dependency notice\n"),
            )

        val prepared = prepare(listOf(main, dependency))

        assertEquals("main licence text\n", File(prepared.root, "LICENSE.txt").readText())
        assertEquals("main notice\n", File(prepared.root, "NOTICE.txt").readText())
        assertEquals(
            "dependency licence text\n",
            File(prepared.root, DEPENDENCY_PREFIX + "/LICENSE.txt").readText(),
        )
        assertEquals(
            "dependency notice\n",
            File(prepared.root, DEPENDENCY_PREFIX + "/NOTICE.txt").readText(),
        )
    }

    @Test
    fun aPlainRuntimeTextFileTwoArchivesFillDifferentlyIsStillRefused() {
        val main = mainArchive(extra = mapOf("custom_phrase.txt" to "你\tni\t1\n"))
        val dependency = dependencyArchive(extra = mapOf("custom_phrase.txt" to "你\tni\t2\n"))

        val error =
            assertReason(SchemePackageImportError.Reason.CONFLICTING_FILE) {
                prepare(listOf(main, dependency))
            }

        assertEquals(listOf("custom_phrase.txt"), error.paths)
    }

    @Test
    fun theCompressedArchivesShareOneTotalBudget() {
        val main = mainArchive()
        val dependency = dependencyArchive()
        val together = main.length() + dependency.length()
        assertTrue("either archive fits alone", main.length() < together && dependency.length() < together)

        assertReason(SchemePackageImportError.Reason.TOO_LARGE) {
            prepare(listOf(main, dependency), limits = SchemePackageLimits(archiveBytes = together - 1))
        }
        val prepared = prepare(listOf(main, dependency), limits = SchemePackageLimits(archiveBytes = together))
        assertEquals(listOf("bopomofo_tw", "terra_pinyin"), prepared.schemaIds)
    }

    @Test
    fun anAdvertisedEntryMustMatchThePackagesOwnConfiguration() {
        val main = mainArchive(extra = mapOf("default.yaml" to defaultConfig("bopomofo_tw", "terra_pinyin")))

        val error =
            assertReason(SchemePackageImportError.Reason.INVALID_SCHEMA) {
                prepare(listOf(main, dependencyArchive()), advertised = listOf("bopomofo_tw"))
            }

        val message = error.message.orEmpty()
        assertTrue("the configured list is named: " + message, message.contains("terra_pinyin"))
        assertTrue("the advertised list is named: " + message, message.contains("bopomofo_tw"))
    }

    @Test
    fun aCustomPatchThatBypassesTheAdvertisedListIsRefusedWithoutDeletingIt() {
        val patch = "patch:\n  schema_list:\n    - schema: terra_pinyin\n"
        val main = mainArchive(extra = mapOf("default.custom.yaml" to patch))
        val target = File(root, "advertised-custom")

        val error =
            assertReason(SchemePackageImportError.Reason.INVALID_SCHEMA) {
                SchemePackageImportBuilder.prepareArchives(
                    archives = listOf(main, dependencyArchive()),
                    target = target,
                    advertisedSchemas = listOf("bopomofo_tw"),
                )
            }

        assertTrue("the configured list is named: " + error.message, error.message.orEmpty().contains("terra_pinyin"))
        assertEquals(
            "the author's patch is not silently deleted",
            patch,
            File(target, "default.custom.yaml").readText(),
        )
    }

    @Test
    fun thePackagesOwnConfigurationStaysAuthoritativeWhenItMatchesTheAdvertisement() {
        val config = defaultConfig("bopomofo_tw")
        val main = mainArchive(extra = mapOf("default.yaml" to config))

        val prepared = prepare(listOf(main, dependencyArchive()), advertised = listOf("bopomofo_tw"))

        assertEquals(config, File(prepared.root, "default.yaml").readText())
        assertEquals(listOf("bopomofo_tw"), prepared.schemaIds)
    }

    @Test
    fun theAppsPreludeDefaultIsCuratedToTheAdvertisedInputMethods() {
        val prepared =
            SchemePackageImportBuilder.prepareArchives(
                archives = listOf(mainArchive(), dependencyArchive()),
                target = File(root, "advertised-prelude"),
                supplement = { root ->
                    // The app's prelude supplement lists every root schema when the package ships none.
                    File(root, "default.yaml").writeText(defaultConfig("bopomofo_tw", "terra_pinyin"))
                },
                advertisedSchemas = listOf("bopomofo_tw"),
            )

        assertEquals(listOf("bopomofo_tw"), prepared.schemaIds)
        assertEquals(defaultConfig("bopomofo_tw"), File(prepared.root, "default.yaml").readText())
    }

    private fun prepare(
        archives: List<File>,
        advertised: List<String> = emptyList(),
        limits: SchemePackageLimits = SchemePackageLimits(),
    ): PreparedSchemeImport {
        val target = File(root, "out-" + outputs++)
        return SchemePackageImportBuilder.prepareArchives(
            archives = archives,
            target = target,
            limits = limits,
            advertisedSchemas = advertised,
        )
    }

    private fun stagedPaths(root: File): Set<String> =
        root
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .toSet()

    private fun assertReason(
        reason: SchemePackageImportError.Reason,
        block: () -> Unit,
    ): SchemePackageImportError {
        try {
            block()
        } catch (error: SchemePackageImportError) {
            assertEquals(error.message, reason, error.reason)
            return error
        }
        fail("expected " + reason)
        throw AssertionError()
    }

    private fun defaultConfig(vararg schemas: String): String =
        "config_version: \"1\"\nschema_list:\n" + schemas.joinToString("") { "  - schema: " + it + "\n" }

    private fun dictionary(
        name: String,
        rows: String,
    ): String = "---\nname: " + name + "\nversion: \"1\"\nsort: by_weight\n...\n" + rows

    /** The entry archive: one input method, no default.yaml, like the upstream rime-bopomofo ZIP. */
    private fun mainArchive(extra: Map<String, String> = emptyMap()): File =
        zip(
            "rime-bopomofo-6085c9a38a4a728047862b33d67eee18aa86f3b9",
            linkedMapOf(
                "bopomofo_tw.schema.yaml" to SCHEMA,
                "LICENSE" to "main licence\n",
                "AUTHORS" to "Gong Chen\n",
                "README.md" to "# 注音\n",
            ) + extra,
        )

    /** A dependency archive: its schema and dictionary stay at the package root. */
    private fun dependencyArchive(extra: Map<String, String> = emptyMap()): File =
        zip(
            DEPENDENCY_ARCHIVE,
            linkedMapOf(
                "terra_pinyin.schema.yaml" to TERRA_SCHEMA,
                "terra_pinyin.dict.yaml" to dictionary("terra_pinyin", "你\tni\t1\n"),
                "LICENSE" to "dependency licence\n",
                "AUTHORS" to "Gong Chen\n",
                "README.md" to "# 地球拼音\n",
            ) + extra,
        )

    private fun cantoneseArchive(): File =
        zip(
            "rime-cantonese-259f0e48bba840c3a2e0d117539e96937f3d89bc",
            linkedMapOf(
                "jyut6ping3.schema.yaml" to
                    "schema:\n  schema_id: jyut6ping3\n  name: 粵拼\ntranslator:\n  dictionary: jyut6ping3\n",
                "jyut6ping3.dict.yaml" to dictionary("jyut6ping3", "你\tnei5\t1\n"),
                "LICENSE-CC-BY" to "Attribution 4.0 International\n",
                "LICENSE-ODbL" to "ODC Open Database License\n",
            ),
        )

    private fun zip(
        wrapper: String,
        files: Map<String, String>,
    ): File {
        val file = File(root, wrapper + "-" + outputs + ".zip")
        file.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for ((path, text) in files) {
                zip.putNextEntry(ZipEntry(wrapper + "/" + path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private companion object {
        const val DEPENDENCY_ARCHIVE = "rime-terra-pinyin-723e51bc266cf9464530c1ddedb856aa18e3da34"
        const val DEPENDENCY_PREFIX = "source-repositories/" + DEPENDENCY_ARCHIVE
        const val SCHEMA = "schema:\n  schema_id: bopomofo_tw\n  name: 注音\ntranslator:\n  dictionary: terra_pinyin\n"
        const val TERRA_SCHEMA =
            "schema:\n  schema_id: terra_pinyin\n  name: 地球拼音\ntranslator:\n  dictionary: terra_pinyin\n"
    }
}
