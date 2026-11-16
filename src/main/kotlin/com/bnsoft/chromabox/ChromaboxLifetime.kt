package com.bnsoft.chromabox

import com.bnsoft.chromabox.engine.EditorLensController
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope

/**
 * Parent of every [EditorLensController], through [controllers]. The platform disposes it when the plugin is unloaded,
 * so no editor keeps a reference to plugin classes and the plugin can be updated or removed without an IDE restart.
 * Lenses added or removed by other plugins count as a settings change, so editors, the palette and the index catch up.
 */
@Service(Service.Level.APP)
class ChromaboxLifetime(scope: CoroutineScope) : Disposable {
    /** The parent of the editor controllers; replaced when they're disposed. */
    var controllers: Disposable = Disposer.newDisposable(this, "Chromabox editor controllers")
        private set

    init {
        ChromaboxHost.LENS_EP.addChangeListener(scope) { ChromaboxSettings.getInstance().notifyChanged() }
    }

    fun disposeControllers() {
        Disposer.dispose(controllers)
        controllers = Disposer.newDisposable(this, "Chromabox editor controllers")
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(): ChromaboxLifetime = service()
    }
}

/** Attaches to editors that were already open when the plugin was installed, and detaches before it's unloaded. */
class ChromaboxDynamicPluginListener : DynamicPluginListener {
    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return
        EditorFactory.getInstance().allEditors
            .filter(ChromaboxEditorFactoryListener::isSupported)
            .forEach { EditorLensController.getOrCreate(it, ChromaboxHost) }
    }

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return
        ChromaboxLifetime.getInstance().disposeControllers()
    }

    private companion object {
        const val PLUGIN_ID = "com.bnsoft.chromabox"
    }
}
