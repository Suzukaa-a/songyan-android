package app.songyan.ime.settings

import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry

/** A string in the device's own language, so tests do not depend on the emulator's locale. */
internal fun str(
    @StringRes id: Int,
    vararg args: Any,
): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
