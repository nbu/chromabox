package com.bnsoft.chromabox

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.bindValue
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
            for (format in ColorFormat.entries.filter { !it.lensBacked || it.lensId in registered }) {
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
                if (format == ColorFormat.FLOAT_TUPLES) {
                    indent {
                        row(ChromaboxBundle.message("settings.floatTupleFileTypes")) {
                            textField()
                                .align(AlignX.FILL)
                                .bindText(state::floatTupleFileTypes)
                                .applyToComponent { emptyText.text = ChromaboxBundle.message("settings.floatTupleFileTypes.empty") }
                                .comment(ChromaboxBundle.message("settings.floatTupleFileTypes.comment"))
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
        group(ChromaboxBundle.message("settings.contrast")) {
            buttonsGroup {
                row(ChromaboxBundle.message("settings.contrastBadges")) {
                    for (badges in ContrastBadges.entries) radioButton(ChromaboxBundle.message("contrastBadges.${badges.name}"), badges)
                }.comment(ChromaboxBundle.message("settings.contrastBadges.comment"))
            }.bind(state::contrastBadges)
            row {
                checkBox(ChromaboxBundle.message("settings.showApca"))
                    .bindSelected(state::showApca)
                    .comment(ChromaboxBundle.message("settings.showApca.comment"))
            }
        }
        group(ChromaboxBundle.message("settings.palette")) {
            row(ChromaboxBundle.message("settings.nearDuplicateThreshold")) {
                spinner(0.5..10.0, 0.1)
                    .bindValue(state::nearDuplicateThreshold)
                    .comment(ChromaboxBundle.message("settings.nearDuplicateThreshold.comment"))
            }
        }
        if (PluginManagerCore.getPlugin(PluginId.getId("org.intellij.plugins.markdown"))?.isEnabled == true) {
            group(ChromaboxBundle.message("settings.markdownPreview")) {
                row {
                    checkBox(ChromaboxBundle.message("settings.showInMarkdownPreview"))
                        .bindSelected(state::showInMarkdownPreview)
                        .comment(ChromaboxBundle.message("settings.showInMarkdownPreview.comment"))
                }
            }
        }
        group(ChromaboxBundle.message("settings.simulation")) {
            row {
                checkBox(ChromaboxBundle.message("settings.rememberSimulation"))
                    .bindSelected(settings::rememberSimulation)
                    .comment(ChromaboxBundle.message("settings.rememberSimulation.comment"))
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
