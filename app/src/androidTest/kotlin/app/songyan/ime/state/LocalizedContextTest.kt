package app.songyan.ime.state

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Text the settings screens show follows the app's own language, not the system's. */
@RunWith(AndroidJUnit4::class)
class LocalizedContextTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val manager get() = context.getSystemService(LocaleManager::class.java)

    @After
    fun clearTheAppLanguage() {
        if (Build.VERSION.SDK_INT >= 33) manager.applicationLocales = LocaleList.getEmptyLocaleList()
    }

    @Test
    fun theBuiltInSourceIsNamedInTheAppsLanguage() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        manager.applicationLocales = LocaleList.forLanguageTags("zh-CN")
        assertEquals("松烟内置", localizedContext(context).getString(R.string.source_builtin_name))
        manager.applicationLocales = LocaleList.forLanguageTags("en")
        assertEquals("Songyan built-in", localizedContext(context).getString(R.string.source_builtin_name))
    }
}
