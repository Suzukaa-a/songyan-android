package app.songyan.ime.ime

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** An IME window has a service lifetime and a separate visible lifetime. */
class ImeViewOwners :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    private var destroyed = false

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun install(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    fun show() {
        if (destroyed) return
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun hide() {
        if (destroyed) return
        registry.currentState = Lifecycle.State.CREATED
    }

    /**
     * The framework still calls onFinishInputView while it tears a service down; a destroyed
     * lifecycle refuses every move, and the resulting exception used to kill the input method
     * process, so the next switch back to it started cold and the keyboard stayed blank for seconds.
     */
    fun destroy() {
        destroyed = true
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
