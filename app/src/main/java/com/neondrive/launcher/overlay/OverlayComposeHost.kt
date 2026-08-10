package com.neondrive.launcher.overlay

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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

/**
 * Compose умеет жить только внутри дерева с владельцами жизненного цикла.
 * У окна, добавленного напрямую в WindowManager, такого дерева нет — поэтому
 * владельцев мы предоставляем сами.
 */
class OverlayComposeHost(private val context: Context) :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private var started = false

    /** Создаёт View с Compose-содержимым, готовую к добавлению в WindowManager. */
    fun createView(content: @Composable () -> Unit): View {
        if (!started) {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            started = true
        }
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayComposeHost)
            setViewTreeViewModelStoreOwner(this@OverlayComposeHost)
            setViewTreeSavedStateRegistryOwner(this@OverlayComposeHost)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }
    }

    fun destroy() {
        if (!started) return
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        started = false
    }
}
