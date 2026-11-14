package com.bnsoft.chromabox

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

class ChromaboxConfigurable : BoundConfigurable(ChromaboxBundle.message("settings.title")) {
    private val settings get() = ChromaboxSettings.getInstance()
    private val state get() = settings.state

    override fun createPanel(): DialogPanel = panel {
        buttonsGroup(ChromaboxBundle.message("settings.mode")) {
            for (mode in DisplayMode.entries) {
                row { radioButton(ChromaboxBundle.message("mode.${mode.name}"), mode) }
            }
        }.bind(state::mode)
        group(ChromaboxBundle.message("settings.formats")) {
            // Only formats whose lens is registered, so formats that aren't implemented yet stay hidden.
            val registered = ChromaboxHost.registeredLenses.mapTo(HashSet()) { it.id }
            for (format in ColorFormat.entries.filter { it.lensId in registered }) {
                lateinit var enabled: Cell<JBCheckBox>
                row {
                    enabled = checkBox(ChromaboxBundle.message("format.${format.name}"))
                        .bindSelected({ settings.isLensEnabled(format.lensId) }, { settings.setLensEnabled(format.lensId, it) })
                }
                if (format == ColorFormat.HEX) {
                    indent {
                        row {
                            checkBox(ChromaboxBundle.message("settings.shortForms"))
                                .bindSelected(state::shortForms)
                                .comment(ChromaboxBundle.message("settings.shortForms.comment"))
                                .enabledIf(enabled.selected)
                        }
                    }
                }
                if (format == ColorFormat.INTEGER) {
                    indent {
                        buttonsGroup {
                            row(ChromaboxBundle.message("settings.intOrder")) {
                                for (order in IntColorOrder.entries) {
                                    radioButton(ChromaboxBundle.message("intOrder.${order.name}"), order).enabledIf(enabled.selected)
                                }
                            }
                        }.bind(state::intOrder)
                    }
                }
            }
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
