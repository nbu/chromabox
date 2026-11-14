package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ContrastLevel
import com.bnsoft.chromabox.color.ContrastResult
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.RoundRectangle2D

/** A compact `4.6:1 AA` badge after a pair of colors (C-02.2); its color shows the outcome. */
class ContrastBadgeRenderer(val result: ContrastResult) : EditorCustomElementRenderer {
    val text: String = result.ratioText + " " + ChromaboxBundle.message("contrast.level.${result.level.name}")

    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        inlay.editor.contentComponent.getFontMetrics(font(inlay.editor)).stringWidth(text) + JBUI.scale(PADDING) * 2 + JBUI.scale(MARGIN) * 2

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val font = font(inlay.editor)
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            g2.font = font
            val metrics = g2.fontMetrics
            val margin = JBUI.scale(MARGIN)
            val height = metrics.height
            val x = targetRegion.x + margin.toDouble()
            val y = targetRegion.y + (targetRegion.height - height) / 2.0
            val width = targetRegion.width - margin * 2.0
            val arc = JBUI.scale(6).toDouble()
            g2.color = background(result.level)
            g2.fill(RoundRectangle2D.Double(x, y, width, height.toDouble(), arc, arc))
            g2.color = FOREGROUND
            g2.drawString(text, (x + JBUI.scale(PADDING)).toFloat(), (y + metrics.ascent).toFloat())
        } finally {
            g2.dispose()
        }
    }

    private fun font(editor: Editor): Font {
        val base = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        return base.deriveFont(base.size2D * 0.85f)
    }

    private companion object {
        const val PADDING = 4
        const val MARGIN = 3
        val FOREGROUND = JBColor(Color(0x1f, 0x1f, 0x1f), Color(0xf0, 0xf0, 0xf0))

        fun background(level: ContrastLevel): Color = when (level) {
            ContrastLevel.FAIL -> JBColor(Color(0xf7, 0xc6, 0xc6), Color(0x7a, 0x2e, 0x2e))
            ContrastLevel.AA_LARGE -> JBColor(Color(0xfa, 0xe1, 0xb0), Color(0x6e, 0x52, 0x1f))
            ContrastLevel.AA -> JBColor(Color(0xcd, 0xe8, 0xc8), Color(0x2f, 0x5a, 0x34))
            ContrastLevel.AAA -> JBColor(Color(0xb8, 0xdc, 0xf5), Color(0x24, 0x4e, 0x6e))
        }
    }
}
