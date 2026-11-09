package app.songyan.ime

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.songyan.ime.deploy.DeploymentStarter
import app.songyan.ime.keyboard.support.SettingsTarget
import app.songyan.ime.settings.SongyanSettingsScreen
import app.songyan.ime.settings.state.ImeId
import app.songyan.ime.settings.state.IncomingPackageHolder
import app.songyan.ime.settings.state.SettingsAppStateHolder

/** Hosts the Compose settings UI; the IME service class is the input method this app ships. */
class SongyanSettingsActivity : ComponentActivity() {
    /** The page the keyboard asked for, until the UI has navigated to it. */
    private var pendingTarget by mutableStateOf<SettingsTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation) still holds the intent that started it; only a fresh
        // start may act on its target, or every rotation would jump back to that page.
        if (savedInstanceState == null) {
            pendingTarget = targetOf(intent)
            IncomingPackageIntents.packageUri(intent)?.let(IncomingPackageHolder::offer)
        }
        // Opening settings is the user-visible moment where the first deployment may start.
        DeploymentStarter.ensureDeployed(this)
        val imeComponent =
            ImeId(
                packageName = packageName,
                className = SongyanInputMethodService::class.java.name,
            )
        setContent {
            SongyanSettingsScreen(
                imeComponent = imeComponent,
                versionName = BuildConfig.VERSION_NAME,
                target = pendingTarget,
                onTargetHandled = { pendingTarget = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTarget = targetOf(intent)
        IncomingPackageIntents.packageUri(intent)?.let(IncomingPackageHolder::offer)
    }

    override fun onResume() {
        super.onResume()
        SettingsAppStateHolder.current.refresh()
        DeploymentStarter.ensureDeployed(this)
    }

    /** The target an intent names, or null for a plain launch (launcher icon, notification). */
    private fun targetOf(intent: Intent?): SettingsTarget? =
        intent?.getStringExtra(SettingsTarget.EXTRA)?.let { SettingsTarget.fromId(it) }
}
