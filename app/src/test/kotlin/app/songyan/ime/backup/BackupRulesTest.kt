package app.songyan.ime.backup

import app.songyan.ime.content.LocalQuickContentStore
import app.songyan.ime.content.ToolStore
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Auto Backup and device-transfer rules, parsed from the shipped XML and compared with the paths
 * [AppStorage] (and the content store) really use, so a renamed directory cannot silently drop out of
 * a backup. The policy is written out in the comments of the two XML files.
 */
class BackupRulesTest {
    private val files = File("/files")
    private val storage = AppStorage(files)

    private class Rules(
        val includes: Set<String>,
        val excludes: Set<String>,
    )

    private fun rel(file: File): String = file.relativeTo(files).path

    private fun parse(
        name: String,
        section: String?,
    ): Rules {
        val xml = File("src/main/res/xml/$name.xml")
        assertTrue("run from the app module: ${xml.absolutePath}", xml.isFile)
        val root =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(xml)
                .documentElement
        val scope =
            if (section == null) {
                root
            } else {
                root.getElementsByTagName(section).item(0) as Element
            }

        fun collect(tag: String): Set<String> {
            val nodes = scope.getElementsByTagName(tag)
            return (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .filter { it.getAttribute("domain") == "file" }
                .map { it.getAttribute("path") }
                .toSet()
        }
        return Rules(collect("include"), collect("exclude"))
    }

    private val legacy get() = parse("backup_rules", null)
    private val cloud get() = parse("data_extraction_rules", "cloud-backup")
    private val transfer get() = parse("data_extraction_rules", "device-transfer")

    private val userData
        get() =
            listOf(
                rel(storage.userRoot),
                rel(storage.contentDir),
                rel(storage.layoutsDir),
                rel(storage.appearanceDir),
                rel(File(files, ToolStore.DIRECTORY_NAME)),
                "datastore",
            )

    private val rebuildable
        get() =
            listOf(
                storage.versionsDir,
                storage.deployRoot,
                storage.importStagingDir,
                storage.trashDir,
                storage.requestsDir,
                storage.activeFile,
                storage.activeLockFile,
                storage.deployLockFile,
                storage.leaseFile,
                storage.learningBackupDir,
            ).map { rel(it) }

    private fun covered(
        rules: Rules,
        path: String,
    ): Boolean =
        rules.includes.any { path == it || path.startsWith("$it/") } &&
            rules.excludes.none { path == it || path.startsWith("$it/") }

    @Test
    fun everythingTheUserMadeIsInEveryBackup() {
        for ((name, rules) in listOf("legacy" to legacy, "cloud" to cloud, "transfer" to transfer)) {
            for (path in userData) assertTrue("$name includes $path", path in rules.includes)
            assertTrue("$name keeps the engine feature choices", covered(rules, rel(storage.featureSettingsFile)))
        }
    }

    @Test
    fun rebuildableAndPrivateStateIsInNoBackup() {
        for ((name, rules) in listOf("legacy" to legacy, "cloud" to cloud, "transfer" to transfer)) {
            for (path in rebuildable) assertFalse("$name must not back up $path", covered(rules, path))
            assertFalse("$name has the built-in source", covered(rules, rel(storage.sourceDir(SourceIds.BUILTIN))))
            val clipboard = rel(File(storage.contentDir, LocalQuickContentStore.CLIPBOARD_FILE_NAME))
            assertFalse("$name has the clipboard history", covered(rules, clipboard))
            assertTrue(
                "$name keeps the phrases",
                covered(rules, rel(File(storage.contentDir, LocalQuickContentStore.FILE_NAME))),
            )
            assertTrue(
                "$name keeps the emoji recents",
                covered(rules, rel(File(storage.contentDir, "emoji/recent.json"))),
            )
            assertFalse("$name backs up the whole rime directory", rel(storage.rimeRoot) in rules.includes)
        }
    }

    @Test
    fun theToolsAndTheirMetadataTravelWithEveryBackup() {
        val tools = File(files, ToolStore.DIRECTORY_NAME)
        val definition = rel(File(tools, "sample" + ToolStore.TOOL_FILE_SUFFIX))
        val metadata = rel(File(tools, ToolStore.INDEX_FILE_NAME))
        val lock = rel(File(tools, ToolStore.LOCK_FILE_NAME))
        for ((name, rules) in listOf("legacy" to legacy, "cloud" to cloud, "transfer" to transfer)) {
            assertTrue("$name keeps a user tool", covered(rules, definition))
            assertTrue("$name keeps the enabled/pinned/order metadata", covered(rules, metadata))
            assertFalse("$name must not back up the tools lock", covered(rules, lock))
        }
    }

    @Test
    fun theSchemaSwitchChoicesTravelWithEveryBackup() {
        val choice = rel(File(storage.optionsDir, "70/6c/73.txt"))
        for ((name, rules) in listOf("legacy" to legacy, "cloud" to cloud, "transfer" to transfer)) {
            assertTrue("$name keeps the schema switch choices", covered(rules, choice))
        }
    }

    /** Imports that can grow past the 25 MB cloud limit, which would make the system drop the whole backup. */
    private val largeImports get() = listOf(rel(storage.sourcesDir), rel(storage.stackedDictionariesDir))

    @Test
    fun largeImportsAreOutOfTheCloudButInDeviceTransfer() {
        for (path in largeImports) {
            for (rules in listOf(legacy, cloud)) assertFalse("cloud must not carry $path", covered(rules, path))
            assertTrue("transfer carries $path", path in transfer.includes)
        }
    }

    @Test
    fun largeModelsAreOutOfTheCloudButInDeviceTransfer() {
        val grammar = rel(File(storage.grammarModelsDir, "songyan-model-x.gram"))
        val prediction = rel(storage.predictionDatabaseFile("predict-x.db"))
        for (rules in listOf(legacy, cloud)) {
            assertFalse(covered(rules, grammar))
            assertFalse(covered(rules, prediction))
            assertFalse(covered(rules, rel(storage.learningSnapshotsDir)))
        }
        assertTrue(covered(transfer, grammar))
        assertTrue(covered(transfer, prediction))
        assertTrue(covered(transfer, rel(storage.featureSettingsFile)))
        assertFalse(covered(transfer, rel(File(storage.featuresDir, "features.lock"))))
        assertTrue(covered(transfer, rel(File(storage.learningSnapshotsDir, "snapshot-1.json"))))
    }

    @Test
    fun cloudAndLegacyRulesAgree() {
        assertEquals(legacy.includes, cloud.includes)
        assertEquals(legacy.excludes, cloud.excludes)
    }
}
