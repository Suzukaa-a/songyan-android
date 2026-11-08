package app.songyan.ime.deploy

import app.songyan.ime.storage.AppStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DeployRequestTest {
    private fun extras(vararg values: Pair<String, String>): (String) -> String? {
        val map = values.toMap()
        return { key -> map[key] }
    }

    @Test
    fun aRequestWithoutIdOrModeIsRefused() {
        assertNull(DeployRequest.from(requestId = null, mode = "builtin", force = false, stringExtra = extras()))
        assertNull(DeployRequest.from(requestId = "../x", mode = "builtin", force = false, stringExtra = extras()))
        assertNull(DeployRequest.from(requestId = "r1", mode = null, force = false, stringExtra = extras()))
        assertNull(DeployRequest.from(requestId = "r1", mode = "unknown", force = false, stringExtra = extras()))
    }

    @Test
    fun requestsThatNameSomethingNeedIt() {
        assertNull(DeployRequest.from("r1", "activate-source", false, extras()))
        assertNull(DeployRequest.from("r1", "import-dictionary-url", false, extras()))
        assertNull(DeployRequest.from("r1", "download-file", false, extras()))
        assertEquals(
            "https://a/m.gram",
            DeployRequest
                .from(
                    "r1",
                    "download-file",
                    false,
                    extras(DeployContract.EXTRA_URL to "https://a/m.gram"),
                )?.url,
        )
        val small =
            DeployRequest.from(
                "r1",
                "download-file",
                false,
                extras(DeployContract.EXTRA_URL to "https://a/h.json", DeployContract.EXTRA_MAX_BYTES to "262144"),
            )
        assertEquals(262_144L, small?.maxBytes)
        assertNull(
            "a malformed limit falls back to the model limit",
            DeployRequest
                .from(
                    "r1",
                    "download-file",
                    false,
                    extras(DeployContract.EXTRA_URL to "https://a/h.json", DeployContract.EXTRA_MAX_BYTES to "-1"),
                )?.maxBytes,
        )
        val activate =
            DeployRequest.from(
                "r1",
                "activate-source",
                false,
                extras(
                    DeployContract.EXTRA_SOURCE_ID to "scheme-0123",
                ),
            )
        assertEquals(DeployMode.ACTIVATE_SOURCE, activate?.mode)
        assertEquals("scheme-0123", activate?.sourceId)
        val link =
            DeployRequest.from(
                "r2",
                "import-dictionary-url",
                false,
                extras(
                    DeployContract.EXTRA_URL to "https://a/b.dict.yaml",
                ),
            )
        assertEquals("https://a/b.dict.yaml", link?.url)
        assertNull(DeployRequest.from("r4", "import-scheme-url", false, extras()))
        val update =
            DeployRequest.from(
                "r4",
                "import-scheme-url",
                false,
                extras(
                    DeployContract.EXTRA_URL to "https://a/p.zip",
                    DeployContract.EXTRA_SOURCE_ID to "scheme-0123",
                ),
            )
        assertEquals("https://a/p.zip", update?.url)
        assertEquals("the update target travels with the request", "scheme-0123", update?.sourceId)
        assertNull(DeployRequest.from("r5", "restore-source", false, extras()))
        assertEquals(
            "scheme-0123",
            DeployRequest
                .from("r5", "restore-source", false, extras(DeployContract.EXTRA_SOURCE_ID to "scheme-0123"))
                ?.sourceId,
        )
        val builtin = DeployRequest.from("r3", "builtin", true, extras())
        assertTrue(builtin!!.force)
    }

    @Test
    fun theWaitingCompanionCompletionsCanBeFoundToMakeWayForASwitch() {
        val queue = DeployQueue()
        queue.offer(DeployRequest("a", DeployMode.COMPLETE_COMPANIONS))
        queue.offer(DeployRequest("b", DeployMode.COMPLETE_COMPANIONS))
        queue.offer(DeployRequest("c", DeployMode.REDEPLOY_ACTIVE))
        queue.offer(DeployRequest("d", DeployMode.COMPLETE_COMPANIONS))

        assertEquals(listOf("a", "b", "d"), queue.waiting(DeployMode.COMPLETE_COMPANIONS))
        assertEquals(listOf("c"), queue.waiting(DeployMode.REDEPLOY_ACTIVE))
    }

    @Test
    fun requestsRunInArrivalOrderAndOnlyIdenticalRebuildsMerge() {
        val queue = DeployQueue()
        assertTrue(queue.offer(DeployRequest("a", DeployMode.IMPORT_DICTIONARY)))
        assertTrue(queue.offer(DeployRequest("b", DeployMode.IMPORT_SCHEME)))
        assertTrue(queue.offer(DeployRequest("c", DeployMode.REDEPLOY_ACTIVE)))
        // A retry tapped while the same rebuild waits rides along with it.
        assertFalse(queue.offer(DeployRequest("d", DeployMode.REDEPLOY_ACTIVE)))
        assertTrue(queue.offer(DeployRequest("e", DeployMode.BUILTIN, force = false)))
        assertTrue(
            "a forced rebuild is different work",
            queue.offer(DeployRequest("f", DeployMode.BUILTIN, force = true)),
        )
        // Imports never merge, even when identical: each carries its own files.
        assertTrue(queue.offer(DeployRequest("g", DeployMode.IMPORT_DICTIONARY)))
        assertTrue(queue.offer(DeployRequest("h", DeployMode.IMPORT_DICTIONARY)))
        assertTrue("a merged request still waits", queue.contains("d"))
        assertFalse(queue.contains("z"))

        val order = generateSequence { queue.poll() }.toList()
        assertFalse("taken requests no longer wait", queue.contains("a") || queue.contains("d"))
        assertEquals(listOf("a", "b", "c", "e", "f", "g", "h"), order.map { it.request.requestId })
        assertEquals(listOf("c", "d"), order[2].requestIds)
        assertEquals(listOf("a"), order[0].requestIds)
    }

    @Test
    fun everyRequestKeepsItsOwnStatus() {
        val storage = AppStorage(Files.createTempDirectory("files").toFile())
        val store = DeployRequestStore(storage)
        store.write("r1", RequestStatus(RequestState.RUNNING))
        store.write("r2", RequestStatus(RequestState.FAILED, problem = "MISSING_DEPENDENCY", paths = listOf("a.lua")))
        store.write("r1", RequestStatus(RequestState.SUCCEEDED, sourceId = "user-pinyin"))

        val first = store.read("r1")!!
        assertEquals(RequestState.SUCCEEDED, first.state)
        assertEquals("user-pinyin", first.sourceId)
        assertTrue(first.updatedAtEpochMs > 0)
        val second = store.read("r2")!!
        assertEquals("MISSING_DEPENDENCY", second.problem)
        assertEquals(listOf("a.lua"), second.paths)
        assertNull(store.read("missing"))
        assertNull(store.read("../active"))
        assertNull(DeployRequestStore.parse("{\"state\":\"BOGUS\"}"))
    }

    @Test
    fun aRunningDownloadCarriesHowMuchHasArrived() {
        val storage = AppStorage(Files.createTempDirectory("files").toFile())
        val store = DeployRequestStore(storage)
        store.write("r1", RequestStatus(RequestState.RUNNING, downloadedBytes = 3_000_000, totalBytes = 12_000_000))
        store.write("r2", RequestStatus(RequestState.RUNNING, downloadedBytes = 5))

        val first = store.read("r1")!!
        assertEquals(3_000_000L, first.downloadedBytes)
        assertEquals(12_000_000L, first.totalBytes)
        assertNull(store.read("r2")!!.totalBytes)
        store.write("r1", RequestStatus(RequestState.SUCCEEDED))
        assertNull(store.read("r1")!!.downloadedBytes)
    }
}
