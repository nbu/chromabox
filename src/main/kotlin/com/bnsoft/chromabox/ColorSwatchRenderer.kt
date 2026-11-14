package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/**
 * A small rounded swatch of [color], as an inlay or, in the gutter, as an icon. [outOfGamut] adds a corner mark:
 * [color] is the clamped sRGB version of a color the literal defines outside sRGB.
 */
class ColorSwatchRenderer(val color: Color, val outOfGamut: Boolean = false) : EditorCustomElementRenderer, Icon {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = swatchSize(inlay) + JBUI.scale(GAP)

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val size = swatchSize(inlay)
        paintSwatch(g, targetRegion.x.toDouble() + JBUI.scale(GAP) / 2.0, targetRegion.y + (targetRegion.height - size) / 2.0, size)
    }

    override fun getIconWidth() = JBUI.scale(ICON_SIZE)

    override fun getIconHeight() = JBUI.scale(ICON_SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) = paintSwatch(g, x.toDouble(), y.toDouble(), iconWidth)

    private fun paintSwatch(g: Graphics, x: Double, y: Double, size: Int) {
        val arc = JBUI.scale(3).toDouble()
        val shape = RoundRectangle2D.Double(x, y, size.toDouble(), size.toDouble(), arc, arc)

        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val clip = g2.clip
            g2.clip(shape)
            if (color.alpha < 255) paintCheckerboard(g2, x, y, size)
            g2.color = color
            g2.fill(shape)
            if (outOfGamut) paintGamutMark(g2, x, y, size)
            g2.clip = clip
            g2.color = BORDER
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
    }

    // A triangle in the top-right corner, in black or white, whichever stands out against the swatch.
    private fun paintGamutMark(g2: Graphics2D, x: Double, y: Double, size: Int) {
        val mark = size * 0.45
        val triangle = Path2D.Double().apply {
            moveTo(x + size - mark, y)
            lineTo(x + size, y)
            lineTo(x + size, y + mark)
            closePath()
        }
        val luminance = (0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue) / 255
        g2.color = if (luminance > 0.5) Color.BLACK else Color.WHITE
        g2.fill(triangle)
    }

    private fun paintCheckerboard(g2: Graphics2D, x: Double, y: Double, size: Int) {
        val cell = size / 2.0
        for (row in 0..1) for (col in 0..1) {
            g2.color = if ((row + col) % 2 == 0) Color.WHITE else Color.LIGHT_GRAY
            g2.fill(Rectangle2D.Double(x + col * cell, y + row * cell, cell, cell))
        }
    }

    private fun swatchSize(inlay: Inlay<*>): Int = (inlay.editor.lineHeight * 0.7).toInt().coerceAtLeast(JBUI.scale(8))

    companion object {
        // Space around the swatch, split evenly between both sides.
        private const val GAP = 4

        // Gutter icons are 12 by 12, scaled.
        private const val ICON_SIZE = 12
        private val BORDER = JBColor(Color(0, 0, 0, 90), Color(255, 255, 255, 90))

        fun of(color: ChromaColor) = ColorSwatchRenderer(color.toAwt(), outOfGamut = !color.inGamut)
    }
}
