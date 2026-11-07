package app.songyan.ime.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The M3 storage contract, driven through the public API only: definitions are imported and
 * exported with the real songyantool.json codec, while metadata, ordering, pinning and the
 * single delete-undo slot are checked by reopening the store.
 */
class ToolStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: Path by lazy { temporary.newFolder("tools").toPath() }

    private val builtins: List<ToolDefinition>
        get() = BuiltinToolDefinitions.all(OpenCCToolConfig.S2T)

    private fun store(): ToolStore = ToolStore(root, builtins)

    private fun definitionJson(
        id: String,
        names: Map<String, String> = mapOf("en" to "Example"),
        steps: String = """{"type":"width","mode":"toHalf"}""",
    ): ByteArray {
        val name = names.entries.joinToString(",") { (tag, value) -> "\"" + tag + "\":\"" + value + "\"" }
        return """{"formatVersion":1,"id":"$id","name":{$name},"steps":[$steps]}""".toByteArray(Charsets.UTF_8)
    }

    private fun toolFile(id: String): Path = root.resolve(id + ToolStore.TOOL_FILE_SUFFIX)

    private fun writeToolFile(id: String) {
        Files.createDirectories(root)
        Files.write(toolFile(id), definitionJson(id))
    }

    private fun userIds(catalog: ToolCatalog): List<String> =
        catalog.tools.filter { it.origin == ToolOrigin.USER }.map { it.id }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("expected the operation to fail")
        } catch (error: AssertionError) {
            throw error
        } catch (error: Throwable) {
            error
        }

    private suspend fun storeFailureOf(block: suspend () -> Unit): ToolStoreError {
        val error = failureOf(block)
        assertTrue("expected ToolStoreError but was " + error, error is ToolStoreError)
        return error as ToolStoreError
    }

    // Reading

    @Test
    fun aMissingDirectoryListsTheExamplesWithoutCreatingIt() =
        runBlocking {
            val absent = root.resolve("absent")
            val store = ToolStore(absent.toFile(), builtins)
            val catalog = store.load()
            assertEquals(
                listOf(
                    BuiltinToolDefinitions.SCRIPT_CONVERSION_ID,
                    BuiltinToolDefinitions.WIDTH_ID,
                    BuiltinToolDefinitions.LETTER_CASE_ID,
                ),
                catalog.tools.map { it.id },
            )
            assertTrue(catalog.tools.all { it.origin == ToolOrigin.BUILTIN })
            assertTrue(catalog.tools.all { it.metadata.enabled })
            assertTrue(catalog.tools.none { it.metadata.pinned })
            assertEquals(listOf(0, 1, 2), catalog.tools.map { it.metadata.order })
            assertNull(catalog.deletedToolId)
            assertFalse(catalog.canUndo)
            assertEquals(emptyList<StoredTool>(), catalog.pinnedTools)
            assertFalse("loading must not create the directory", Files.exists(absent))
        }

    @Test
    fun anOrphanDefinitionIsListedWithDefaultMetadata() =
        runBlocking {
            writeToolFile("user-orphan")
            val catalog = store().load()
            val orphan = catalog.tools.first { it.id == "user-orphan" }
            assertEquals(ToolOrigin.USER, orphan.origin)
            assertEquals(ToolMetadata(enabled = true, pinned = false, order = 3), orphan.metadata)
        }

    @Test
    fun aBrokenDefinitionFileIsSkippedAndReported() =
        runBlocking {
            Files.createDirectories(root)
            Files.write(toolFile("broken"), "{not a tool".toByteArray(Charsets.UTF_8))
            writeToolFile("user-ok")
            val issues = mutableListOf<ToolStoreError>()
            val catalog = store().load { issues += it }
            assertEquals(listOf("user-ok"), userIds(catalog))
            assertEquals(1, issues.size)
            val reason = issues.single().reason
            assertTrue(reason is ToolStoreError.Reason.ToolFileRejected)
            reason as ToolStoreError.Reason.ToolFileRejected
            assertEquals("broken" + ToolStore.TOOL_FILE_SUFFIX, reason.file)
            assertEquals(ToolDefinitionError.Reason.Unreadable, reason.error.reason)
        }

    @Test
    fun loadingAnEmptyDirectoryWritesNothing() =
        runBlocking {
            Files.createDirectories(root)
            val catalog = store().load()
            assertEquals(3, catalog.tools.size)
            assertTrue("a read must not create the index or the lock", Files.list(root).use { it.toList() }.isEmpty())
        }

    @Test
    fun aUserFileCannotShadowABuiltinIdentifier() =
        runBlocking {
            writeToolFile(BuiltinToolDefinitions.WIDTH_ID)
            val issues = mutableListOf<ToolStoreError>()
            val catalog = store().load { issues += it }
            assertEquals(3, catalog.tools.size)
            assertEquals(1, issues.size)
            assertEquals(
                ToolStoreError.Reason.BuiltinIdentifier(BuiltinToolDefinitions.WIDTH_ID),
                issues.single().reason,
            )
        }

    @Test
    fun aBrokenIndexFallsBackToDefaultsAndIsReported() =
        runBlocking {
            Files.createDirectories(root)
            Files.writeString(root.resolve(ToolStore.INDEX_FILE_NAME), "not json")
            writeToolFile("user-ok")
            val issues = mutableListOf<ToolStoreError>()
            val catalog = store().load { issues += it }
            assertEquals(1, issues.size)
            assertTrue(issues.single().reason is ToolStoreError.Reason.MetadataRejected)
            val tool = catalog.tools.first { it.id == "user-ok" }
            assertTrue(tool.metadata.enabled)
            assertFalse(tool.metadata.pinned)
        }

    @Test
    fun aHandEditedIndexCannotExceedThePinLimitOrInventTools() =
        runBlocking {
            Files.createDirectories(root)
            writeToolFile("user-ok")
            val index =
                """
                {"version":1,"tools":[
                  {"id":"builtin-width","enabled":true,"pinned":true,"order":0},
                  {"id":"builtin-letter-case","enabled":true,"pinned":true,"order":1},
                  {"id":"user-ok","enabled":true,"pinned":true,"order":2},
                  {"id":"ghost","enabled":true,"pinned":true,"order":3}
                ]}
                """.trimIndent()
            Files.writeString(root.resolve(ToolStore.INDEX_FILE_NAME), index)
            val catalog = store().load()
            assertEquals(4, catalog.tools.size)
            assertFalse(catalog.tools.any { it.id == "ghost" })
            assertEquals(
                listOf(BuiltinToolDefinitions.WIDTH_ID, BuiltinToolDefinitions.LETTER_CASE_ID),
                catalog.pinnedTools.map { it.id },
            )
        }

    // Import, copy and export

    @Test
    fun anImportedDefinitionIsPersistedWithItsAuthoredNames() =
        runBlocking {
            val store = store()
            val names = mapOf("zh-Hans" to "示例", "zh-Hant" to "範例", "ja" to "例", "en" to "Example")
            val imported = store.importDefinition(definitionJson("user-example", names))
            assertEquals("user-example", imported.id)
            assertEquals(ToolOrigin.USER, imported.origin)
            assertEquals(ToolMetadata(enabled = true, pinned = false, order = 3), imported.metadata)
            assertEquals(names, imported.definition.name)
            assertArrayEquals(imported.definition.encoded(), Files.readAllBytes(toolFile("user-example")))
            val reopened = store().load()
            assertEquals(
                names,
                reopened.tools
                    .first { it.id == "user-example" }
                    .definition.name,
            )
        }

    @Test
    fun importingTheSameIdentifierReplacesTheDefinitionButKeepsItsMetadata() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-same"))
            store.setEnabled("user-same", false)
            store.setPinned("user-same", true)
            val before =
                store
                    .load()
                    .tools
                    .first { it.id == "user-same" }
                    .metadata
            val updated =
                store.importDefinition(
                    definitionJson("user-same", steps = """{"type":"case","mode":"upper"}"""),
                )
            assertEquals(before, updated.metadata)
            assertEquals(listOf(ToolStep.CaseTransform(ToolStep.CaseMode.UPPER)), updated.definition.steps)
            assertEquals(
                before,
                store()
                    .load()
                    .tools
                    .first { it.id == "user-same" }
                    .metadata,
            )
        }

    @Test
    fun aDefinitionTheRealCodecRejectsTouchesNothing() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-keep"))
            val error = failureOf { store.importDefinition("[]".toByteArray(Charsets.UTF_8)) }
            assertTrue(error is ToolDefinitionError)
            assertEquals(listOf("user-keep"), userIds(store().load()))
        }

    @Test
    fun importingAFileUsesTheRealFileDecoder() =
        runBlocking {
            val source = temporary.newFile("sample.songyantool.json").toPath()
            Files.write(source, definitionJson("user-file"))
            val imported = store().importDefinitionFile(source)
            assertEquals("user-file", imported.id)
            assertTrue(Files.exists(toolFile("user-file")))
            val error = failureOf { store().importDefinitionFile(root.resolve("missing.songyantool.json")) }
            assertEquals(ToolDefinitionError.Reason.NotAFile, (error as ToolDefinitionError).reason)
        }

    @Test
    fun importingABuiltinIdentifierIsRefusedWithoutWriting() =
        runBlocking {
            val store = store()
            val error = storeFailureOf { store.importDefinition(definitionJson(BuiltinToolDefinitions.WIDTH_ID)) }
            assertEquals(
                ToolStoreError.Reason.BuiltinIdentifier(BuiltinToolDefinitions.WIDTH_ID),
                error.reason,
            )
            assertFalse(Files.exists(toolFile(BuiltinToolDefinitions.WIDTH_ID)))
            assertEquals(emptyList<String>(), userIds(store().load()))
        }

    @Test
    fun copyingAnExampleKeepsItsNamesAndGetsANewIdentifier() =
        runBlocking {
            val store = store()
            val example = BuiltinToolDefinitions.width()
            val copied = store.copyExample(example, "user-copy")
            assertEquals("user-copy", copied.id)
            assertEquals(example.name, copied.definition.name)
            assertEquals(example.steps, copied.definition.steps)
            val exported = ToolDefinition.decode(store.export("user-copy"))
            assertEquals(example.name, exported.name)
            assertEquals(example.steps, exported.steps)
        }

    @Test
    fun copyingCanRenameTheNewTool() =
        runBlocking {
            val copied =
                store().copyExample(
                    BuiltinToolDefinitions.width(),
                    "user-named",
                    mapOf("zh-Hans" to "我的全角半角"),
                )
            assertEquals(mapOf("zh-Hans" to "我的全角半角"), copied.definition.name)
        }

    @Test
    fun copyingOntoAnExistingIdentifierKeepsItsMetadata() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-taken"))
            store.setEnabled("user-taken", false)
            store.setPinned("user-taken", true)
            val before =
                store
                    .load()
                    .tools
                    .first { it.id == "user-taken" }
                    .metadata
            val copied = store.copyExample(BuiltinToolDefinitions.width(), "user-taken")
            assertEquals(before, copied.metadata)
            assertEquals(BuiltinToolDefinitions.width().steps, copied.definition.steps)
        }

    @Test
    fun exportingABuiltinReturnsItsDefinition() =
        runBlocking {
            assertArrayEquals(BuiltinToolDefinitions.width().encoded(), store().export(BuiltinToolDefinitions.WIDTH_ID))
            val error = storeFailureOf { store().export("no-such-tool") }
            assertEquals(ToolStoreError.Reason.UnknownTool("no-such-tool"), error.reason)
        }

    // Metadata

    @Test
    fun enabledAndPinnedStateSurvivesAReopen() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-a"))
            store.setEnabled(BuiltinToolDefinitions.WIDTH_ID, false)
            store.setPinned("user-a", true)
            val reopened = store().load()
            assertFalse(
                reopened.tools
                    .first { it.id == BuiltinToolDefinitions.WIDTH_ID }
                    .metadata.enabled,
            )
            assertTrue(
                reopened.tools
                    .first { it.id == "user-a" }
                    .metadata.pinned,
            )
            assertEquals(listOf("user-a"), reopened.pinnedTools.map { it.id })
            store.setEnabled("user-a", false)
            assertFalse(
                store()
                    .load()
                    .tools
                    .first { it.id == "user-a" }
                    .metadata.enabled,
            )
        }

    @Test
    fun aThirdPinIsRefusedUntilOneIsFreed() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-a"))
            store.importDefinition(definitionJson("user-b"))
            store.setPinned("user-a", true)
            store.setPinned("user-b", true)
            val error = storeFailureOf { store.setPinned(BuiltinToolDefinitions.WIDTH_ID, true) }
            assertEquals(ToolStoreError.Reason.PinLimit(ToolStore.MAXIMUM_PINNED), error.reason)
            store.setPinned("user-a", false)
            val catalog = store.setPinned(BuiltinToolDefinitions.WIDTH_ID, true)
            assertEquals(listOf(BuiltinToolDefinitions.WIDTH_ID, "user-b"), catalog.pinnedTools.map { it.id })
        }

    @Test
    fun movingReordersAcrossExamplesAndUserToolsAndClamps() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-a"))
            store.importDefinition(definitionJson("user-b"))
            val moved = store.move("user-b", 0)
            assertEquals("user-b", moved.tools.first().id)
            assertEquals((0 until moved.tools.size).toList(), moved.tools.map { it.metadata.order })
            assertEquals(
                "user-b",
                store()
                    .load()
                    .tools
                    .first()
                    .id,
            )
            val last = store.move("user-b", 99)
            assertEquals("user-b", last.tools.last().id)
            val first = store.move("user-a", -5)
            assertEquals("user-a", first.tools.first().id)
            val error = storeFailureOf { store.move("no-such-tool", 0) }
            assertEquals(ToolStoreError.Reason.UnknownTool("no-such-tool"), error.reason)
        }

    // Deletion and undo

    @Test
    fun deletingKeepsOneExactUndoWithItsEnabledPinAndOrderState() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-a"))
            store.setEnabled("user-a", false)
            store.move("user-a", 0)
            store.setPinned("user-a", true)
            val before =
                store
                    .load()
                    .tools
                    .first { it.id == "user-a" }
                    .metadata
            val bytesBefore = store.export("user-a")
            val afterDelete = store.delete("user-a")
            assertNull(afterDelete.tools.firstOrNull { it.id == "user-a" })
            assertEquals("user-a", afterDelete.deletedToolId)
            assertTrue(afterDelete.canUndo)
            assertFalse(Files.exists(toolFile("user-a")))
            assertTrue(store().load().canUndo)

            val restored = store().undoDelete()
            val tool = restored.tools.first { it.id == "user-a" }
            assertEquals(before, tool.metadata)
            assertArrayEquals(bytesBefore, store().export("user-a"))
            assertArrayEquals(bytesBefore, Files.readAllBytes(toolFile("user-a")))
            assertNull(restored.deletedToolId)
            assertEquals(
                before,
                store()
                    .load()
                    .tools
                    .first { it.id == "user-a" }
                    .metadata,
            )
        }

    @Test
    fun onlyTheMostRecentDeletionCanBeUndone() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-a"))
            store.importDefinition(definitionJson("user-b"))
            store.delete("user-a")
            val afterSecond = store.delete("user-b")
            assertEquals("user-b", afterSecond.deletedToolId)
            val restored = store.undoDelete()
            assertTrue(restored.tools.any { it.id == "user-b" })
            assertFalse(restored.tools.any { it.id == "user-a" })
            assertNull(restored.deletedToolId)
            val error = storeFailureOf { store.undoDelete() }
            assertEquals(ToolStoreError.Reason.NothingToUndo, error.reason)
        }

    @Test
    fun aBuiltinOrAnUnknownToolCannotBeDeleted() =
        runBlocking {
            val store = store()
            val builtin = storeFailureOf { store.delete(BuiltinToolDefinitions.WIDTH_ID) }
            assertEquals(
                ToolStoreError.Reason.BuiltinNotDeletable(BuiltinToolDefinitions.WIDTH_ID),
                builtin.reason,
            )
            val unknown = storeFailureOf { store.delete("no-such-tool") }
            assertEquals(ToolStoreError.Reason.UnknownTool("no-such-tool"), unknown.reason)
        }

    // Failure safety

    /**
     * A store whose catalog is above [ToolStore.MAXIMUM_INDEX_BYTES] once written, so a metadata
     * write is refused by the real byte budget instead of by a simulated full disk. The app ships
     * three examples, but the catalog is caller-supplied and a directory restored from a backup can
     * hold more examples than this build accepts.
     */
    private fun storeAboveTheMetadataBudget(): ToolStore =
        ToolStore(
            root,
            List(METADATA_BUDGET_ENTRIES) { position ->
                BuiltinToolDefinitions.width().copy(id = "filler-example-" + position)
            },
        )

    private fun indexFile(): Path = root.resolve(ToolStore.INDEX_FILE_NAME)

    private fun undoFile(): Path = root.resolve(ToolStore.DELETED_FILE_NAME)

    private fun assertMetadataBudgetFailure(error: Throwable) {
        assertTrue(
            "expected the metadata budget refusal but was " + error,
            error is ContentException && error.code == ContentErrorCode.OVERSIZE,
        )
    }

    @Test
    fun aMetadataWriteFailureRestoresThePreviousDefinition() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-saved"))
            store.setEnabled("user-saved", false)
            store.setPinned("user-saved", true)
            val definitionBefore = Files.readAllBytes(toolFile("user-saved"))
            val indexBefore = Files.readAllBytes(indexFile())

            val refused =
                failureOf {
                    val replacement = definitionJson("user-saved", steps = """{"type":"case","mode":"upper"}""")
                    storeAboveTheMetadataBudget().importDefinition(replacement)
                }

            assertMetadataBudgetFailure(refused)
            assertArrayEquals(
                "a save the caller was told failed must not keep the new definition",
                definitionBefore,
                Files.readAllBytes(toolFile("user-saved")),
            )
            assertArrayEquals(indexBefore, Files.readAllBytes(indexFile()))
        }

    @Test
    fun aMetadataWriteFailureLeavesNoDefinitionForANewTool() =
        runBlocking {
            val refused = failureOf { storeAboveTheMetadataBudget().importDefinition(definitionJson("user-new")) }

            assertMetadataBudgetFailure(refused)
            assertFalse(
                "a tool whose save failed must not appear on the next listing",
                Files.exists(toolFile("user-new")),
            )
            assertEquals(emptyList<String>(), userIds(store().load()))
        }

    @Test
    fun aMetadataWriteFailureKeepsTheDeletedToolAndThePreviousUndo() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-kept"))
            store.importDefinition(definitionJson("user-earlier"))
            store.delete("user-earlier")
            val keptBefore = Files.readAllBytes(toolFile("user-kept"))
            val undoBefore = Files.readAllBytes(undoFile())
            val indexBefore = Files.readAllBytes(indexFile())

            val refused = failureOf { storeAboveTheMetadataBudget().delete("user-kept") }

            assertMetadataBudgetFailure(refused)
            assertArrayEquals(keptBefore, Files.readAllBytes(toolFile("user-kept")))
            assertArrayEquals(
                "the delete failed, so the undo the user already had must still be the one kept",
                undoBefore,
                Files.readAllBytes(undoFile()),
            )
            assertArrayEquals(indexBefore, Files.readAllBytes(indexFile()))
            assertEquals("user-earlier", store().load().deletedToolId)
        }

    @Test
    fun aMetadataWriteFailureDoesNotRestoreTheToolOnUndo() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-undo"))
            store.setEnabled("user-undo", false)
            store.setPinned("user-undo", true)
            store.delete("user-undo")
            val undoBefore = Files.readAllBytes(undoFile())
            val indexBefore = Files.readAllBytes(indexFile())

            val refused = failureOf { storeAboveTheMetadataBudget().undoDelete() }

            assertMetadataBudgetFailure(refused)
            assertFalse(
                "the undo was refused, so the tool must not be back on disk",
                Files.exists(toolFile("user-undo")),
            )
            assertArrayEquals(undoBefore, Files.readAllBytes(undoFile()))
            assertArrayEquals(indexBefore, Files.readAllBytes(indexFile()))

            val restored = store().undoDelete()
            assertEquals(
                ToolMetadata(enabled = false, pinned = true, order = 3),
                restored.tools.first { it.id == "user-undo" }.metadata,
            )
            assertNull(restored.deletedToolId)
        }

    @Test
    fun aMutationRefusesAnIndexItCannotRead() =
        runBlocking {
            assumeTrue(
                "needs POSIX permissions",
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
            )
            val store = store()
            store.importDefinition(definitionJson("user-locked"))
            store.setEnabled("user-locked", false)
            store.setPinned("user-locked", true)
            val definitionBefore = Files.readAllBytes(toolFile("user-locked"))
            val indexBefore = Files.readAllBytes(indexFile())

            Files.setPosixFilePermissions(indexFile(), PosixFilePermissions.fromString("---------"))
            try {
                val replacement = definitionJson("user-locked", steps = """{"type":"case","mode":"upper"}""")
                val saveError = storeFailureOf { store.importDefinition(replacement) }
                assertTrue(
                    "expected a metadata rejection but was " + saveError.reason,
                    saveError.reason is ToolStoreError.Reason.MetadataRejected,
                )
                assertArrayEquals(
                    "a definition must not change while its metadata could not be read",
                    definitionBefore,
                    Files.readAllBytes(toolFile("user-locked")),
                )
                val pinError = storeFailureOf { store.setPinned("user-locked", false) }
                assertTrue(pinError.reason is ToolStoreError.Reason.MetadataRejected)
            } finally {
                Files.setPosixFilePermissions(indexFile(), PosixFilePermissions.fromString("rw-------"))
            }

            assertArrayEquals(indexBefore, Files.readAllBytes(indexFile()))
            val tool = store().load().tools.first { it.id == "user-locked" }
            assertFalse(tool.metadata.enabled)
            assertTrue(tool.metadata.pinned)
        }

    @Test
    fun aCorruptIndexIsRefusedInsteadOfBeingRewritten() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-kept"))
            store.setPinned("user-kept", true)
            val broken = """{"version":1,"tools":[""".toByteArray(Charsets.UTF_8)
            Files.write(indexFile(), broken)

            val refused = storeFailureOf { store.setEnabled("user-kept", false) }

            assertTrue(
                "expected a metadata rejection but was " + refused.reason,
                refused.reason is ToolStoreError.Reason.MetadataRejected,
            )
            assertArrayEquals(
                "metadata this build cannot read must not be replaced by defaults",
                broken,
                Files.readAllBytes(indexFile()),
            )
        }

    @Test
    fun aCorruptDefinitionIsRefusedInsteadOfLosingItsMetadata() =
        runBlocking {
            val store = store()
            store.importDefinition(definitionJson("user-kept"))
            store.importDefinition(definitionJson("user-damaged"))
            store.setEnabled("user-damaged", false)
            store.setPinned("user-damaged", true)
            val definitionBefore = Files.readAllBytes(toolFile("user-damaged"))
            val keptBefore = Files.readAllBytes(toolFile("user-kept"))
            val indexBefore = Files.readAllBytes(indexFile())
            val damaged = "{not a tool".toByteArray(Charsets.UTF_8)
            Files.write(toolFile("user-damaged"), damaged)

            // The listing still shows every tool it could read and names the damaged file.
            val issues = mutableListOf<ToolStoreError>()
            val listed = store().load { issues += it }
            assertEquals(listOf("user-kept"), userIds(listed))
            val reported = issues.single().reason
            assertTrue(reported is ToolStoreError.Reason.ToolFileRejected)
            assertEquals(
                "user-damaged" + ToolStore.TOOL_FILE_SUFFIX,
                (reported as ToolStoreError.Reason.ToolFileRejected).file,
            )

            // A mutation would rewrite the index without the damaged tool, so it is refused instead.
            val refused = storeFailureOf { store.setEnabled("user-kept", false) }
            assertTrue(
                "expected the damaged definition to be named but was " + refused.reason,
                refused.reason is ToolStoreError.Reason.ToolFileRejected,
            )
            assertEquals(
                "user-damaged" + ToolStore.TOOL_FILE_SUFFIX,
                (refused.reason as ToolStoreError.Reason.ToolFileRejected).file,
            )
            assertArrayEquals(
                "a refused mutation must not drop the state of a tool it could not read",
                indexBefore,
                Files.readAllBytes(indexFile()),
            )
            assertArrayEquals(damaged, Files.readAllBytes(toolFile("user-damaged")))
            assertArrayEquals(keptBefore, Files.readAllBytes(toolFile("user-kept")))
            val refusedSave = storeFailureOf { store.importDefinition(definitionJson("user-new")) }
            assertTrue(
                "a new tool must be refused while a definition cannot be read",
                refusedSave.reason is ToolStoreError.Reason.ToolFileRejected,
            )
            assertFalse("a refused save must not write its definition", Files.exists(toolFile("user-new")))

            // Repairing the file brings the enabled and pinned state back exactly.
            Files.write(toolFile("user-damaged"), definitionBefore)
            val repaired = store().load().tools.first { it.id == "user-damaged" }
            assertFalse(repaired.metadata.enabled)
            assertTrue(repaired.metadata.pinned)
        }

    @Test
    fun anUnreadableDefinitionIsRefusedInsteadOfLosingItsMetadata() =
        runBlocking {
            assumeTrue(
                "needs POSIX permissions",
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
            )
            val store = store()
            store.importDefinition(definitionJson("user-locked"))
            store.setEnabled("user-locked", false)
            store.setPinned("user-locked", true)
            val indexBefore = Files.readAllBytes(indexFile())

            Files.setPosixFilePermissions(toolFile("user-locked"), PosixFilePermissions.fromString("---------"))
            try {
                val refused = storeFailureOf { store.setEnabled("user-locked", true) }
                assertTrue(
                    "expected the unreadable definition to be named but was " + refused.reason,
                    refused.reason is ToolStoreError.Reason.ToolFileUnreadable,
                )
                assertEquals(
                    "user-locked" + ToolStore.TOOL_FILE_SUFFIX,
                    (refused.reason as ToolStoreError.Reason.ToolFileUnreadable).file,
                )
                assertArrayEquals(
                    "a refused mutation must not drop the state of a tool it could not read",
                    indexBefore,
                    Files.readAllBytes(indexFile()),
                )
            } finally {
                Files.setPosixFilePermissions(toolFile("user-locked"), PosixFilePermissions.fromString("rw-------"))
            }

            val tool = store().load().tools.first { it.id == "user-locked" }
            assertFalse(tool.metadata.enabled)
            assertTrue(tool.metadata.pinned)
        }

    // Concurrency

    @Test
    fun concurrentWritesAreSerializedInsteadOfLost() =
        runBlocking {
            val store = store()
            coroutineScope {
                (0 until 8)
                    .map { index ->
                        async(Dispatchers.Default) { store.importDefinition(definitionJson("user-" + index)) }
                    }.awaitAll()
            }
            val afterImports = store().load()
            assertEquals((0 until 8).map { "user-" + it }.toSet(), userIds(afterImports).toSet())
            assertEquals((0 until afterImports.tools.size).toList(), afterImports.tools.map { it.metadata.order })
            coroutineScope {
                (0 until 8)
                    .map { index ->
                        async(Dispatchers.Default) { store.setEnabled("user-" + index, false) }
                    }.awaitAll()
            }
            assertTrue(
                store()
                    .load()
                    .tools
                    .filter { it.origin == ToolOrigin.USER }
                    .all { !it.metadata.enabled },
            )
        }

    companion object {
        /**
         * Examples whose metadata exceeds [ToolStore.MAXIMUM_INDEX_BYTES]: the budget is 256 KiB and
         * one written entry is about 140 bytes.
         */
        private const val METADATA_BUDGET_ENTRIES: Int = 2800
    }
}
