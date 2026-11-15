package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.Lens
import com.bnsoft.chromabox.engine.LensContext
import com.bnsoft.chromabox.engine.LensEditor
import com.bnsoft.chromabox.engine.LensMatch
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.ColorChooserService
import com.intellij.ui.awt.RelativePoint

/** CSS gradients (C-04): one wide strip; clicking it lists the stops, and picking one edits that stop only. */
class GradientLens : Lens<Gradient> {
    override val id = ColorFormat.GRADIENTS.lensId

    override fun find(text: CharSequence, context: LensContext): List<LensMatch<Gradient>> =
        Gradients.findAll(text).map { LensMatch(it.range, it.gradient, it.gradient) }

    override fun renderer(match: LensMatch<Gradient>): EditorCustomElementRenderer = ColorStripRenderer.of(match.value)

    override fun editor(match: LensMatch<Gradient>): LensEditor<Gradient>? =
        if (match.value.stops.any { it.write != null }) EDITOR else null

    private companion object {
        val EDITOR = GradientEditor()
    }
}

/** The stop list (C-04.2), then the color picker for the chosen stop. */
class GradientEditor : LensEditor<Gradient> {
    override val commandName get() = ChromaboxBundle.message("command.changeColor")

    override fun show(project: Project?, current: Gradient, location: RelativePoint, onChange: (Gradient) -> Unit, onClose: () -> Unit) {
        var chosen = false
        val step = object : BaseListPopupStep<Int>(ChromaboxBundle.message("gradient.stops"), current.stops.indices.toList()) {
            override fun getTextFor(value: Int) = current.stops[value].text

            override fun getIconFor(value: Int) = ColorSwatchRenderer.of(current.stops[value].color)

            override fun isSelectable(value: Int) = current.stops[value].write != null

            override fun onChosen(selectedValue: Int, finalChoice: Boolean): PopupStep<*>? {
                chosen = true
                return doFinalStep { pickStop(project, current, selectedValue, location, onChange, onClose) }
            }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (!chosen) onClose()
            }
        })
        popup.show(location)
    }

    private fun pickStop(
        project: Project?, gradient: Gradient, index: Int, location: RelativePoint,
        onChange: (Gradient) -> Unit, onClose: () -> Unit,
    ) {
        val initial = gradient.stops[index].color.toAwt()
        val listener = ColorLensEditor.pickerListener(initial) { color -> onChange(gradient.withColor(index, color)) }
        ColorChooserService.getInstance().showPopup(project, initial, listener, location, showAlpha = true, popupCloseListener = onClose)
    }

    override fun format(value: Gradient, original: LensMatch<Gradient>): String? = value.write(original.value)
}
