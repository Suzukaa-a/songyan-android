package app.songyan.ime.settings.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.songyan.ime.settings.platform.LicenseBundle
import app.songyan.ime.settings.platform.LicenseCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the parsed licence material for the list and the detail pages, so opening a licence does
 * not parse the notice document a second time and back/forward keeps the list as it was.
 */
class LicensesViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow<LicenseBundle?>(null)

    /** Null while loading. */
    val bundle: StateFlow<LicenseBundle?> = mutable.asStateFlow()

    init {
        load()
    }

    fun reload() {
        mutable.value = null
        load()
    }

    private fun load() {
        viewModelScope.launch { mutable.value = LicenseCatalog.load(getApplication()) }
    }
}
