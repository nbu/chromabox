package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.LensEditor
import com.bnsoft.chromabox.engine.LensMatch
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorChooserService
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.picker.ColorListener

/** Edits a color with the IDE color picker; [formatter] writes it back in the notation of the original literal. */
class ColorLensEditor(private val formatter: (ChromaColor, LensMatch<ChromaColor>) -> String?) : LensEditor<ChromaColor> {
    override val commandName get() = ChromaboxBundle.message("command.changeColor")

    override fun show(
        project: Project?, current: ChromaColor, location: RelativePoint,
        onChange: (ChromaColor) -> Unit, onClose: () -> Unit,
    ) {
        ColorChooserService.getInstance().showPopup(
            project, current.toAwt(), ColorListener { color, _ -> onChange(ChromaColor.fromAwt(color)) }, location,
            showAlpha = true, popupCloseListener = onClose,
        )
    }

    override fun format(value: ChromaColor, original: LensMatch<ChromaColor>): String? = formatter(value, original)
}
