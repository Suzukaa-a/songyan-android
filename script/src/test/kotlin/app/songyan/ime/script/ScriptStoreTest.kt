package app.songyan.ime.script

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Storage rules that the plan pins down: verbatim sources, a versioned state file, secrets kept
 *  out of it, and a catalog whose order is the single source of truth. */
class ScriptStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var store: ScriptStore

    private val plainWrap =
        object : ScriptStore.KeyWrap {
            override fun wrap(key: SecretKey): ByteArray = key.encoded

            override fun unwrap(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")
        }

    @Before
    fun setUp() {
        store = ScriptStore(folder.newFolder("scripts-root"), plainWrap)
    }

    private fun header(
        name: String = "润色",
        connects: List<String> = listOf("api.example.com"),
        settings: List<ScriptSettingField> =
            listOf(
                ScriptSettingField("endpoint", ScriptSettingKind.URL, "服务地址", secret = false),
                ScriptSettingField("key", ScriptSettingKind.TEXT, "密钥", secret = true),
            ),
        needsAudio: Boolean = false,
    ) = ScriptHeader.Parsed(
        apiVersion = 1,
        name = name,
        description = "把文字改得更通顺",
        connects = connects,
        settings = settings,
        needsAudio = needsAudio,
    )

    @Test
    fun addKeepsSourceVerbatimAndStartsUnconfirmed() {
        val source = ("// @api 1\n// @name run\nexport default async () => 1;").toByteArray(Charsets.UTF_8)
        val entry = store.add(source, header(), ScriptSourceOrigin.Pasted)
        assertEquals("润色", entry.name)
        assertFalse(entry.enabled)
        assertTrue(entry.confirmationPending)
        assertArrayEquals(source, store.source(entry.id))
    }

    @Test
    fun confirmStoresPlainSettingsInStateAndSecretsOutsideIt() {
        val entry = store.add("src".toByteArray(), header(), ScriptSourceOrigin.Pasted)
        store.confirm(
            entry.id,
            header(),
            mapOf("endpoint" to "https://api.example.com", "key" to "s3cret"),
            header().settings,
        )
        val stateText = File(folder.root, "scripts-root/scripts/" + entry.id + "/state.json").readText()
        assertTrue(stateText.contains("api.example.com"))
        assertFalse("the secret must not appear in state.json", stateText.contains("s3cret"))
        assertTrue(File(folder.root, "scripts-root/scripts/" + entry.id + "/secret.bin").isFile)
        val readBack = store.settingsOf(entry.id, header().settings)
        assertEquals("s3cret", readBack["key"])
        assertEquals("https://api.example.com", readBack["endpoint"])
    }

    @Test
    fun confirmClearsThePendingFlagAndADeclarationChangeReopensIt() {
        val entry = store.add("src".toByteArray(), header(), ScriptSourceOrigin.Pasted)
        store.confirm(entry.id, header(), emptyMap(), header().settings)
        assertFalse(store.entry(entry.id)!!.confirmationPending)
        store.replaceSource(
            entry.id,
            "src2".toByteArray(),
            header(connects = listOf("api.example.com", "cdn.example.com")),
        )
        assertTrue(store.entry(entry.id)!!.confirmationPending)
    }

    @Test
    fun reorderAcceptsAPermutationAndRejectsAnythingElse() {
        val first = store.add("a".toByteArray(), header("A"), ScriptSourceOrigin.Pasted)
        val second = store.add("b".toByteArray(), header("B"), ScriptSourceOrigin.Pasted)
        store.reorder(listOf(second.id, first.id))
        assertEquals(listOf("B", "A"), store.list().map { it.name })
        val failed = runCatching { store.reorder(listOf(first.id)) }
        assertTrue("reorder must reject a partial list", failed.isFailure)
    }

    @Test
    fun aFirstConfirmationEnablesTheImportedScript() {
        val entry = store.add("src".toByteArray(), header(), ScriptSourceOrigin.Pasted)
        assertFalse("an unconfirmed import is not enabled yet", entry.enabled)

        val confirmed = store.confirm(entry.id, header(), emptyMap(), header().settings)
        assertTrue("the first confirmation turns the script on", confirmed.enabled)
        assertFalse("and clears the pending flag", confirmed.confirmationPending)
    }

    @Test
    fun reconfirmingAnUpdateKeepsTheUsersEnableChoice() {
        val entry = store.add("src".toByteArray(), header(), ScriptSourceOrigin.Pasted)
        store.confirm(entry.id, header(), emptyMap(), header().settings)
        store.setEnabled(entry.id, false)

        // An update changes the declarations, which asks for a fresh confirmation.
        val updated = header(connects = listOf("api.example.com", "cdn.example.com"))
        store.replaceSource(entry.id, "src2".toByteArray(), updated)
        assertTrue("the update reopens confirmation", store.entry(entry.id)!!.confirmationPending)

        val reconfirmed = store.confirm(entry.id, updated, emptyMap(), updated.settings)
        assertFalse("a disabled script stays disabled after the re-confirmation", reconfirmed.enabled)
        assertFalse("only the pending flag moves", reconfirmed.confirmationPending)
    }

    @Test
    fun availabilityFollowsTheHostCapabilitiesNotJustTheStoredFlag() {
        val text = store.add("text".toByteArray(), header("文字"), ScriptSourceOrigin.Pasted)
        val audio = store.add("audio".toByteArray(), header("听写", needsAudio = true), ScriptSourceOrigin.Pasted)

        val textOnly = store.list().associateBy { it.id }
        assertTrue("a text script needs only text", textOnly.getValue(text.id).availableOnThisHost)
        assertFalse(
            "an @input audio script is unavailable without AUDIO",
            textOnly.getValue(audio.id).availableOnThisHost,
        )
        assertTrue("the declaration itself is still stored", textOnly.getValue(audio.id).needsAudio)

        val audioHost = setOf(ScriptHostCapability.TEXT, ScriptHostCapability.AUDIO)
        val withAudio = store.list(audioHost).associateBy { it.id }
        assertTrue(withAudio.getValue(text.id).availableOnThisHost)
        assertTrue("AUDIO makes the audio script available", withAudio.getValue(audio.id).availableOnThisHost)
        assertFalse(store.entry(audio.id)!!.availableOnThisHost)
        assertTrue(store.entry(audio.id, audioHost)!!.availableOnThisHost)
    }

    @Test
    fun deleteRemovesTheDirectoryAndTheCatalogSlot() {
        val entry = store.add("a".toByteArray(), header(), ScriptSourceOrigin.Pasted)
        store.delete(entry.id)
        assertTrue(store.list().isEmpty())
        assertFalse(File(folder.root, "scripts-root/scripts/" + entry.id).exists())
    }
}
