package com.bnsoft.chromabox

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

class ColorSwatchRenderer(val color: Color) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = swatchSize(inlay) + JBUI.scale(GAP)

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val size = swatchSize(inlay)
        val x = targetRegion.x.toDouble() + JBUI.scale(GAP) / 2.0
        val y = targetRegion.y + (targetRegion.height - size) / 2.0
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
            g2.clip = clip
            g2.color = BORDER
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
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
        private val BORDER = JBColor(Color(0, 0, 0, 90), Color(255, 255, 255, 90))
    }
}
