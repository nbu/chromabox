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

/** A color notation that can be turned on or off; [lensId] is the id of the lens that detects it. */
enum class ColorFormat(val lensId: String, val enabledByDefault: Boolean = true) {
    HEX("color.hex"),
    CSS_FUNCTIONS("color.css-func"),
    CSS_COLOR_4("color.css4"),

    // Too many false positives in prose: "red", "tan", "linen".
    NAMED("color.named", enabledByDefault = false),
    INTEGER("color.int"),
    CONSTRUCTORS("color.constructor"),

    // Ambiguous; only in file types the user picks.
    FLOAT_TUPLES("color.float-tuple", enabledByDefault = false),
    ;

    companion object {
        fun byLensId(id: String): ColorFormat? = entries.find { it.lensId == id }
    }
}

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

        /** Short hex forms; part of the `color.hex` lens. */
        var shortForms: Boolean = true
        var excludedFileTypes: String = DEFAULT_EXCLUDED_FILE_TYPES

        /** Lens id to on/off, only where it differs from [ColorFormat.enabledByDefault], so defaults can still change. */
        var lensOverrides: MutableMap<String, Boolean> = sortedMapOf()
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

    fun isLensEnabled(id: String): Boolean =
        myState.lensOverrides[id] ?: ColorFormat.byLensId(id)?.enabledByDefault ?: true

    /** Doesn't notify listeners; callers that change several settings call [notifyChanged] once. */
    fun setLensEnabled(id: String, enabled: Boolean) {
        val default = ColorFormat.byLensId(id)?.enabledByDefault ?: true
        if (enabled == default) myState.lensOverrides.remove(id) else myState.lensOverrides[id] = enabled
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
