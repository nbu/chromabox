package com.bnsoft.chromabox.markdown

import com.bnsoft.chromabox.ChromaboxSettingsListener
import com.bnsoft.chromabox.PreviewSwatches
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.ui.preview.BrowserPipe
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.ResourceProvider

/**
 * Swatches in the Markdown preview (C-07). The script asks for the document's swatches after every preview update
 * and gets them as a message; it also gets them when settings change. Only loaded with the Markdown plugin (C-07.2).
 */
class ChromaboxPreviewExtension(private val panel: MarkdownHtmlPanel, private val pipe: BrowserPipe) :
    MarkdownBrowserPreviewExtension, ResourceProvider {
    private val requested = object : BrowserPipe.Handler {
        override fun processMessageReceived(data: String): Boolean {
            send()
            return false
        }
    }

    init {
        pipe.subscribe(REQUEST, requested)
        // The panel disposes its extensions, and with them this connection.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ChromaboxSettingsListener.TOPIC, ChromaboxSettingsListener { send() })
    }

    override val scripts = listOf(SCRIPT)

    override val styles = listOf(STYLE)

    override val resourceProvider: ResourceProvider get() = this

    override fun canProvide(resourceName: String) = resourceName == SCRIPT || resourceName == STYLE

    override fun loadResource(resourceName: String): ResourceProvider.Resource? =
        ResourceProvider.loadInternalResource(ChromaboxPreviewExtension::class.java, resourceName, null)

    private fun send() {
        val file = panel.virtualFile ?: return
        ReadAction.nonBlocking<String?> {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@nonBlocking null
            PreviewSwatches.json(PreviewSwatches.find(document, file.fileType, panel.project))
        }
            .coalesceBy(this)
            .expireWith(this)
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess { json -> if (json != null) pipe.send(SWATCHES, json) }
    }

    override fun dispose() = pipe.removeSubscription(REQUEST, requested)

    class Provider : MarkdownBrowserPreviewExtension.Provider {
        override fun createBrowserExtension(panel: MarkdownHtmlPanel): MarkdownBrowserPreviewExtension? =
            panel.browserPipe?.let { ChromaboxPreviewExtension(panel, it) }
    }

    private companion object {
        const val SCRIPT = "chromabox-preview.js"
        const val STYLE = "chromabox-preview.css"

        // Message types; they must match the script's.
        const val REQUEST = "chromabox/request"
        const val SWATCHES = "chromabox/swatches"
    }
}
