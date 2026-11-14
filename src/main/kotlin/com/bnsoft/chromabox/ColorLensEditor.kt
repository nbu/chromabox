package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.LensEditor
import com.bnsoft.chromabox.engine.LensMatch
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorChooserService
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.picker.ColorListener
import java.awt.Color

/** Edits a color with the IDE color picker; [formatter] writes it back in the notation of the original literal. */
class ColorLensEditor(private val formatter: (ChromaColor, LensMatch<ChromaColor>) -> String?) : LensEditor<ChromaColor> {
    override val commandName get() = ChromaboxBundle.message("command.changeColor")

    override fun show(
        project: Project?, current: ChromaColor, location: RelativePoint,
        onChange: (ChromaColor) -> Unit, onClose: () -> Unit,
    ) {
        val initial = current.toAwt()
        ColorChooserService.getInstance().showPopup(
            project, initial, pickerListener(initial, onChange), location, showAlpha = true, popupCloseListener = onClose,
        )
    }

    companion object {
        /**
         * The picker only knows the clamped sRGB color. Until the user picks a different one there's nothing to write,
         * and writing would replace an out-of-gamut value with its clamped version (C-01.e).
         */
        internal fun pickerListener(initial: Color, onChange: (ChromaColor) -> Unit): ColorListener {
            var touched = false
            return ColorListener { color, _ ->
                touched = touched || color != initial
                if (touched) onChange(ChromaColor.fromAwt(color))
            }
        }
    }

    override fun format(value: ChromaColor, original: LensMatch<ChromaColor>): String? = formatter(value, original)
}
