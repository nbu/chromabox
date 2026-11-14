package com.bnsoft.chromabox

import com.bnsoft.chromabox.engine.LensEditor
import com.bnsoft.chromabox.engine.LensMatch
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorChooserService
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.picker.ColorListener
import java.awt.Color

/** Edits a color with the IDE color picker; [formatter] writes it back in the notation of the original literal. */
class ColorLensEditor(private val formatter: (Color, LensMatch<Color>) -> String?) : LensEditor<Color> {
    override val commandName get() = ChromaboxBundle.message("command.changeColor")

    override fun show(project: Project?, current: Color, location: RelativePoint, onChange: (Color) -> Unit, onClose: () -> Unit) {
        ColorChooserService.getInstance().showPopup(
            project, current, ColorListener { color, _ -> onChange(color) }, location,
            showAlpha = true, popupCloseListener = onClose,
        )
    }

    override fun format(value: Color, original: LensMatch<Color>): String? = formatter(value, original)
}
