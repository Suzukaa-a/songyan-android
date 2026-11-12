// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import app.songyan.ime.addon.AddonCapability
import app.songyan.ime.addon.AddonHandshake
import app.songyan.ime.addon.AddonHostInfo
import app.songyan.ime.addon.AddonProtocol
import app.songyan.ime.addon.AddonToolDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The registry: discovery, the enabled switch, the cached handshake that lets a fresh keyboard
 * process list tools without binding, and the last-call record the settings page shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonRegistryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun facts(packageName: String): AddonPackageFacts =
        AddonPackageFacts(
            packageName = packageName,
            serviceClassName = packageName + ".AddonService",
            label = packageName.substringAfterLast('.'),
            versionName = "1.0",
            signerSha256 = "AA".repeat(32),
            permissionLabels = listOf("Microphone"),
        )

    private fun hostInfo(): AddonHostInfo =
        AddonHostInfo(
            protocolMinVersion = AddonProtocol.MIN_VERSION,
            protocolMaxVersion = AddonProtocol.MAX_VERSION,
            hostPackage = AddonProtocol.HOST_PACKAGE,
            hostVersionName = "test",
            hostVersionCode = 1,
            sdkInt = 36,
            capabilities = setOf(AddonCapability.TEXT, AddonCapability.PANEL),
        )

    private fun handshake(vararg tools: String): AddonHandshake =
        AddonHandshake(
            protocolVersion = AddonProtocol.VERSION,
            tools = tools.map { AddonToolDescriptor(it, it.uppercase()) },
            supportsPanel = tools.isNotEmpty(),
            supportsVoice = false,
        )

    private class FakeDiscovery(
        private val packages: List<AddonPackageFacts>,
    ) : AddonDiscovery {
        override suspend fun discover(): List<AddonPackageFacts> = packages
    }

    private class FakeNegotiator(
        private val answers: Map<String, AddonNegotiation>,
    ) : AddonNegotiator {
        var calls: Int = 0
            private set

        override suspend fun negotiate(
            facts: AddonPackageFacts,
            hostInfo: AddonHostInfo,
        ): AddonNegotiation {
            calls += 1
            return answers[facts.packageName] ?: AddonNegotiation.Unavailable("no answer")
        }
    }

    private fun registry(
        store: AddonStore,
        discovery: AddonDiscovery,
        negotiator: AddonNegotiator,
        scope: CoroutineScope,
    ): AddonRegistry =
        AddonRegistry(
            store = store,
            discovery = discovery,
            negotiator = negotiator,
            hostInfo = ::hostInfo,
            scope = scope,
            // The test dispatcher keeps the persisted call record inside advanceUntilIdle.
            ioDispatcher =
                scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as? CoroutineDispatcher
                    ?: Dispatchers.IO,
        )

    @Test
    fun scanNegotiatesRecordsTheEnabledSwitchAndKeepsFailuresTyped() =
        runTest {
            val store = AddonStore(temporary.newFolder("addons").also { it.mkdirs() })
            store.write(StoredAddonState(enabled = setOf("app.one")))
            val discovery = FakeDiscovery(listOf(facts("app.one"), facts("app.two")))
            val negotiator =
                FakeNegotiator(
                    mapOf(
                        "app.one" to AddonNegotiation.Answered(handshake("uppercase")),
                        "app.two" to AddonNegotiation.Unavailable("no common version"),
                    ),
                )
            val registry = registry(store, discovery, negotiator, CoroutineScope(StandardTestDispatcher(testScheduler)))
            val entries = registry.scan(negotiate = true)
            val one = entries.single { it.packageName == "app.one" }
            val two = entries.single { it.packageName == "app.two" }
            assertTrue(one.enabled)
            assertTrue(one.isCompatible)
            assertEquals(listOf("uppercase"), one.tools.map { it.id })
            assertFalse(two.enabled)
            assertFalse(two.isCompatible)
            assertNull(two.handshake)
            assertEquals("no common version", two.negotiationFailure)
            // The compatible handshake is cached for the next process.
            assertEquals(
                listOf("uppercase"),
                store
                    .read()
                    .cached
                    .getValue("app.one")
                    .tools
                    .map { it.id },
            )
        }

    @Test
    fun aFreshRegistryListsCachedToolsWithoutBinding() =
        runTest {
            val directory = temporary.newFolder("addons").also { it.mkdirs() }
            val store = AddonStore(directory)
            val first =
                registry(
                    store,
                    FakeDiscovery(listOf(facts("app.one"))),
                    FakeNegotiator(mapOf("app.one" to AddonNegotiation.Answered(handshake("uppercase")))),
                    CoroutineScope(StandardTestDispatcher(testScheduler)),
                )
            first.scan(negotiate = true)

            val secondNegotiator = FakeNegotiator(emptyMap())
            val second =
                registry(
                    store,
                    FakeDiscovery(listOf(facts("app.one"))),
                    secondNegotiator,
                    CoroutineScope(StandardTestDispatcher(testScheduler)),
                )
            val entries = second.scan(negotiate = false)
            assertEquals(0, secondNegotiator.calls)
            assertEquals(listOf("uppercase"), entries.single().tools.map { it.id })
            assertTrue(entries.single().isCompatible)
        }

    @Test
    fun setEnabledPersistsAndShowsInTheSnapshot() =
        runTest {
            val store = AddonStore(temporary.newFolder("addons").also { it.mkdirs() })
            val registry =
                registry(
                    store,
                    FakeDiscovery(listOf(facts("app.one"))),
                    FakeNegotiator(emptyMap()),
                    CoroutineScope(StandardTestDispatcher(testScheduler)),
                )
            registry.scan(negotiate = false)
            registry.setEnabled("app.one", true)
            assertTrue(registry.entries().single().enabled)
            assertEquals(setOf("app.one"), store.read().enabled)
            registry.setEnabled("app.one", false)
            assertFalse(registry.entries().single().enabled)
            assertEquals(emptySet<String>(), store.read().enabled)
        }

    @Test
    fun setEnabledReportsAWriteFailureAndLeavesTheSwitchOff() =
        runTest {
            val directory = temporary.newFolder("addons")
            // A directory in the state file's place makes every save fail.
            java.io.File(directory, "addons.json").mkdirs()
            val store = AddonStore(directory)
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val registry = registry(store, FakeDiscovery(listOf(facts("app.one"))), FakeNegotiator(emptyMap()), scope)
            registry.scan(negotiate = false)
            assertFalse(registry.setEnabled("app.one", true))
            assertFalse(registry.entries().single().enabled)
        }

    @Test
    fun refreshOnKeyboardShowPicksUpAnEnableWrittenByAnotherRegistry() =
        runTest {
            val directory = temporary.newFolder("addons").also { it.mkdirs() }
            val keyboard =
                registry(
                    AddonStore(directory),
                    FakeDiscovery(listOf(facts("app.one"))),
                    FakeNegotiator(mapOf("app.one" to AddonNegotiation.Answered(handshake("uppercase")))),
                    CoroutineScope(StandardTestDispatcher(testScheduler)),
                )
            // The keyboard process starts disabled: settings has not enabled anything yet.
            keyboard.scan(negotiate = false)
            assertTrue(AddonMenu.entries(keyboard).isEmpty())

            // The 扩展 page is its own registry (a future build may run it in its own process); it
            // writes the enable and the handshake cache to the shared state file.
            val settings =
                registry(
                    AddonStore(directory),
                    FakeDiscovery(listOf(facts("app.one"))),
                    FakeNegotiator(mapOf("app.one" to AddonNegotiation.Answered(handshake("uppercase")))),
                    CoroutineScope(StandardTestDispatcher(testScheduler)),
                )
            settings.scan(negotiate = true)
            assertTrue(settings.setEnabled("app.one", true))

            // The next keyboard show re-reads the file: the menu rows appear without a restart.
            assertTrue(AddonMenu.entries(keyboard).isEmpty())
            keyboard.refreshOnKeyboardShow()
            assertEquals(listOf("app.one" to "one"), AddonMenu.entries(keyboard))
        }

    @Test
    fun aCacheThatCannotBeWrittenDoesNotBreakTheScan() =
        runTest {
            val directory = temporary.newFolder("addons")
            java.io.File(directory, "addons.json").mkdirs()
            val store = AddonStore(directory)
            val registry =
                registry(
                    store,
                    FakeDiscovery(listOf(facts("app.one"))),
                    FakeNegotiator(mapOf("app.one" to AddonNegotiation.Answered(handshake("uppercase")))),
                    CoroutineScope(StandardTestDispatcher(testScheduler)),
                )
            val entries = registry.scan(negotiate = true)
            assertEquals(listOf("uppercase"), entries.single().tools.map { it.id })
        }

    @Test
    fun recordCallUpdatesTheSnapshotAndTheFile() =
        runTest {
            val directory = temporary.newFolder("addons").also { it.mkdirs() }
            val store = AddonStore(directory)
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val registry = registry(store, FakeDiscovery(listOf(facts("app.one"))), FakeNegotiator(emptyMap()), scope)
            registry.scan(negotiate = false)
            registry.recordCall("app.one", AddonCallKinds.TEXT, durationMs = 12, error = "boom")
            val call = registry.entries().single().lastCall ?: error("no call recorded")
            assertEquals(AddonCallKinds.TEXT, call.kind)
            assertEquals(12L, call.durationMs)
            assertEquals("boom", call.error)
            advanceUntilIdle()
            assertEquals(
                "boom",
                store
                    .read()
                    .calls
                    .getValue("app.one")
                    .error,
            )
        }

    @Test
    fun warmScansWithoutBindingAnything() =
        runTest {
            val store = AddonStore(temporary.newFolder("addons").also { it.mkdirs() })
            val negotiator = FakeNegotiator(mapOf("app.one" to AddonNegotiation.Answered(handshake())))
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val registry = registry(store, FakeDiscovery(listOf(facts("app.one"))), negotiator, scope)
            registry.warm()
            advanceUntilIdle()
            assertEquals(1, registry.entries().size)
            assertEquals(0, negotiator.calls)
        }

    @Test
    fun onlyEnabledCompatibleExtensionsAreOfferedToTheKeyboard() =
        runTest {
            val store = AddonStore(temporary.newFolder("addons").also { it.mkdirs() })
            store.write(StoredAddonState(enabled = setOf("app.one")))
            val registry =
                registry(
                    store,
                    FakeDiscovery(listOf(facts("app.one"), facts("app.two"))),
                    FakeNegotiator(
                        mapOf(
                            "app.one" to AddonNegotiation.Answered(handshake("uppercase")),
                            "app.two" to
                                AddonNegotiation.Answered(AddonHandshake.incompatible("two", "Two", "old", 9, 9)),
                        ),
                    ),
                    CoroutineScope(StandardTestDispatcher(testScheduler)),
                )
            registry.scan(negotiate = true)
            assertEquals(listOf("app.one"), registry.enabledCompatible().map { it.packageName })
        }
}
