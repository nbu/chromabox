package com.bnsoft.chromabox

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
import java.awt.LinearGradientPaint
import java.awt.Rectangle
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/**
 * A wide rounded strip, about three swatches wide: a gradient with its stops at their positions (C-04.1), or, with
 * [segments], equal blocks of each color, for a color sequence (C-04.3). In the gutter, a square icon.
 */
class ColorStripRenderer(val stops: List<Pair<Double, Color>>, private val segments: Boolean = false) : EditorCustomElementRenderer, Icon {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = stripWidth(height(inlay)) + JBUI.scale(GAP)

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val height = height(inlay)
        paintStrip(g, targetRegion.x + JBUI.scale(GAP) / 2.0, targetRegion.y + (targetRegion.height - height) / 2.0, stripWidth(height), height)
    }

    override fun getIconWidth() = JBUI.scale(ICON_SIZE)

    override fun getIconHeight() = JBUI.scale(ICON_SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) = paintStrip(g, x.toDouble(), y.toDouble(), iconWidth, iconHeight)

    private fun paintStrip(g: Graphics, x: Double, y: Double, width: Int, height: Int) {
        val arc = JBUI.scale(3).toDouble()
        val shape = RoundRectangle2D.Double(x, y, width.toDouble(), height.toDouble(), arc, arc)
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val clip = g2.clip
            g2.clip(shape)
            if (stops.any { it.second.alpha < 255 }) paintCheckerboard(g2, x, y, width, height)
            if (segments) {
                for ((i, stop) in stops.withIndex()) {
                    g2.color = stop.second
                    g2.fill(Rectangle2D.Double(x + width * i / stops.size.toDouble(), y, width / stops.size.toDouble() + 1, height.toDouble()))
                }
            } else {
                g2.paint = paint(x, width)
                g2.fill(shape)
            }
            g2.clip = clip
            g2.color = BORDER
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
    }

    // LinearGradientPaint needs strictly increasing fractions in 0..1, so equal positions (hard stops) are nudged.
    private fun paint(x: Double, width: Int): LinearGradientPaint {
        val fractions = FloatArray(stops.size)
        var previous = -1f
        for ((i, stop) in stops.withIndex()) {
            val fraction = stop.first.toFloat().coerceIn(0f, 1f)
            fractions[i] = if (fraction <= previous) previous + NUDGE else fraction
            previous = fractions[i]
        }
        // Nudging can push the last ones past 1; scale them back, keeping their order.
        val last = fractions.last()
        if (last > 1f) for (i in fractions.indices) fractions[i] /= last
        return LinearGradientPaint(x.toFloat(), 0f, (x + width).toFloat(), 0f, fractions, stops.map { it.second }.toTypedArray())
    }

    private fun paintCheckerboard(g2: Graphics2D, x: Double, y: Double, width: Int, height: Int) {
        val cell = height / 2.0
        var col = 0
        while (col * cell < width) {
            for (row in 0..1) {
                g2.color = if ((row + col) % 2 == 0) Color.WHITE else Color.LIGHT_GRAY
                g2.fill(Rectangle2D.Double(x + col * cell, y + row * cell, cell, cell))
            }
            col++
        }
    }

    private fun height(inlay: Inlay<*>) = (inlay.editor.lineHeight * 0.7).toInt().coerceAtLeast(JBUI.scale(8))

    private fun stripWidth(height: Int) = height * WIDTH_IN_SWATCHES

    companion object {
        private const val GAP = 4
        private const val ICON_SIZE = 12
        private const val WIDTH_IN_SWATCHES = 3
        private const val NUDGE = 1e-4f
        private val BORDER = JBColor(Color(0, 0, 0, 90), Color(255, 255, 255, 90))

        fun of(gradient: Gradient) = ColorStripRenderer(gradient.stops.map { it.position to it.color.toAwt() })
    }
}
