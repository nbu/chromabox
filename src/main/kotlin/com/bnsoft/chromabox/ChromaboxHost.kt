package com.bnsoft.chromabox

import com.bnsoft.chromabox.engine.Lens
import com.bnsoft.chromabox.engine.LensHost
import com.bnsoft.chromabox.engine.LensPresentation
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.FileType

/** Connects the lens engine to Chromabox settings, lenses and lifetime. */
object ChromaboxHost : LensHost {
    private val LENS_EP = ExtensionPointName<Lens<*>>("com.bnsoft.chromabox.lens")

    private val settings get() = ChromaboxSettings.getInstance()

    override val parentDisposable: Disposable get() = ChromaboxLifetime.getInstance()

    override val presentation: LensPresentation
        get() = when (settings.mode) {
            DisplayMode.BOX_ONLY -> LensPresentation.INLAY_ONLY
            DisplayMode.BOX_WITH_HEX -> LensPresentation.INLAY_WITH_TEXT
            DisplayMode.OFF -> LensPresentation.OFF
        }

    /** Every registered lens, including disabled ones. */
    val registeredLenses: List<Lens<*>> get() = LENS_EP.extensionList

    override val lenses: List<Lens<*>> get() = registeredLenses.filter { settings.isLensEnabled(it.id) }

    override fun isExcluded(fileType: FileType): Boolean = settings.isExcluded(fileType)

    override fun subscribe(disposable: Disposable, listener: () -> Unit) {
        ApplicationManager.getApplication().messageBus.connect(disposable)
            .subscribe(ChromaboxSettingsListener.TOPIC, ChromaboxSettingsListener(listener))
        LENS_EP.addChangeListener(listener, disposable)
    }

    override val maxDocumentLength get() = ChromaboxSettings.MAX_DOCUMENT_LENGTH
}
