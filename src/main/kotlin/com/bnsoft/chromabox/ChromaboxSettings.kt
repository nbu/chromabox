package com.bnsoft.chromabox

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileType
import com.intellij.util.messages.Topic

enum class DisplayMode { BOX_ONLY, BOX_WITH_HEX, OFF }

fun interface ChromaboxSettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC = Topic.create("Chromabox settings", ChromaboxSettingsListener::class.java)
    }
}

@Service(Service.Level.APP)
@State(name = "ChromaboxSettings", storages = [Storage("chromabox.xml")])
class ChromaboxSettings : PersistentStateComponent<ChromaboxSettings.SettingsState> {
    class SettingsState {
        var mode: DisplayMode = DisplayMode.BOX_WITH_HEX
        var shortForms: Boolean = true
        var excludedFileTypes: String = DEFAULT_EXCLUDED_FILE_TYPES
    }

    private var myState = SettingsState()

    override fun getState(): SettingsState = myState

    override fun loadState(state: SettingsState) {
        myState = state
    }

    var mode: DisplayMode
        get() = myState.mode
        set(value) {
            if (myState.mode == value) return
            myState.mode = value
            notifyChanged()
        }

    fun isExcluded(fileType: FileType): Boolean =
        myState.excludedFileTypes.split(',').any { it.trim().equals(fileType.name, ignoreCase = true) }

    fun notifyChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(ChromaboxSettingsListener.TOPIC).settingsChanged()
    }

    companion object {
        // File types where the IDE already shows color swatches (via ElementColorProvider).
        const val DEFAULT_EXCLUDED_FILE_TYPES = "CSS, SCSS, SASS, LESS, Stylus, PostCSS"

        /** Documents larger than this many characters aren't scanned. */
        const val MAX_DOCUMENT_LENGTH = 2_000_000

        fun getInstance(): ChromaboxSettings = service()
    }
}
