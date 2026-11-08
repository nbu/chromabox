package com.bnsoft.chromabox

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class ChromaboxConfigurable : BoundConfigurable(ChromaboxBundle.message("settings.title")) {
    private val state get() = ChromaboxSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        buttonsGroup(ChromaboxBundle.message("settings.mode")) {
            for (mode in DisplayMode.entries) {
                row { radioButton(ChromaboxBundle.message("mode.${mode.name}"), mode) }
            }
        }.bind(state::mode)
        row {
            checkBox(ChromaboxBundle.message("settings.shortForms"))
                .bindSelected(state::shortForms)
                .comment(ChromaboxBundle.message("settings.shortForms.comment"))
        }
        row(ChromaboxBundle.message("settings.excludedFileTypes")) {
            textField()
                .align(AlignX.FILL)
                .bindText(state::excludedFileTypes)
                .comment(ChromaboxBundle.message("settings.excludedFileTypes.comment"))
        }
    }

    override fun apply() {
        super.apply()
        ChromaboxSettings.getInstance().notifyChanged()
    }
}
