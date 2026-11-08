package com.bnsoft.chromabox

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer

/**
 * Parent of every [EditorColorController]. The platform disposes it when the plugin is unloaded, so no editor keeps
 * a reference to plugin classes and the plugin can be updated or removed without an IDE restart.
 */
@Service(Service.Level.APP)
class ChromaboxLifetime : Disposable {
    fun disposeControllers() = Disposer.disposeChildren(this) { true }

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
            .forEach(EditorColorController::getOrCreate)
    }

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return
        ChromaboxLifetime.getInstance().disposeControllers()
    }

    private companion object {
        const val PLUGIN_ID = "com.bnsoft.chromabox"
    }
}
