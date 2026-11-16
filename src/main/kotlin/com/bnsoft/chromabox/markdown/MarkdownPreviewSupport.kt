package com.bnsoft.chromabox.markdown

import com.intellij.openapi.application.ApplicationManager

/**
 * Registered only with the Markdown plugin (C-07.2), so it tells whether preview swatches are available. Refers to no
 * Markdown classes, so it loads without the plugin.
 */
class MarkdownPreviewSupport {
    companion object {
        val isAvailable: Boolean
            get() = ApplicationManager.getApplication().getService(MarkdownPreviewSupport::class.java) != null
    }
}
