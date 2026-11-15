package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.Apca
import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.color.Contrast
import com.bnsoft.chromabox.color.ContrastFix
import com.bnsoft.chromabox.color.ContrastResult
import com.bnsoft.chromabox.engine.LensEditorAccess
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.util.Locale
import javax.swing.JComponent

/**
 * The contrast of a pair in detail (C-02.3): the ratio, results for normal text, large text and UI components, a
 * preview, APCA if turned on (C-02.6), and fixes that change the foreground's lightness until it reaches AA or AAA
 * (C-02.4).
 */
object ContrastPopup {
    private var current: Pair<JBPopup, ContrastPair>? = null

    fun show(access: LensEditorAccess, pair: ContrastPair, location: RelativePoint) {
        current?.let { (popup, shownPair) ->
            if (popup.isVisible && shownPair == pair) return
            popup.cancel()
        }
        lateinit var popup: JBPopup
        val content = content(pair) { fixed ->
            popup.cancel()
            access.write(pair.foreground.range, fixed)
        }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(false)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .setMovable(true)
            // Like a tooltip, it closes once the mouse moves well away from it.
            .setCancelOnMouseOutCallback { event -> isFarFrom(content, event.locationOnScreen) }
            .createPopup()
        current = popup to pair
        popup.show(location)
    }

    // Test hooks.
    internal val shownPair: ContrastPair? get() = current?.second

    internal fun close() {
        current?.first?.cancel()
        current = null
    }

    internal fun content(pair: ContrastPair, onFix: (ChromaColor) -> Unit): JComponent {
        val fg = pair.foreground.color
        val bg = pair.background.color
        val result = Contrast.of(fg, bg)
        fun outcome(threshold: Double) = ChromaboxBundle.message(
            if (result.ratio >= threshold) "contrast.popup.pass" else "contrast.popup.fail",
            ratio(threshold),
        )
        return panel {
            row { label(ChromaboxBundle.message("contrast.popup.ratio", result.ratioText)).bold() }
            if (result.approximate) row { comment(ChromaboxBundle.message("contrast.popup.approximate")) }
            row(ChromaboxBundle.message("contrast.popup.normalText")) {
                label("AA " + outcome(ContrastResult.AA))
                label("AAA " + outcome(ContrastResult.AAA))
            }
            row(ChromaboxBundle.message("contrast.popup.largeText")) {
                label("AA " + outcome(ContrastResult.AA_LARGE))
                label("AAA " + outcome(ContrastResult.AA))
            }
            row(ChromaboxBundle.message("contrast.popup.ui")) {
                label("AA " + outcome(ContrastResult.AA_LARGE))
            }
            if (ChromaboxSettings.getInstance().state.showApca) {
                val lc = Apca.lc(Contrast.composite(fg, opaque(bg)), opaque(bg))
                row(ChromaboxBundle.message("contrast.popup.apca")) { label(String.format(Locale.ROOT, "Lc %.1f", lc)) }
            }
            row { cell(Preview(fg, bg)) }
            for ((level, target) in listOf("AA" to ContrastResult.AA, "AAA" to ContrastResult.AAA)) {
                val fixed = ContrastFix.suggest(fg, bg, target) ?: continue
                val ratio = Contrast.of(fixed, bg).ratioText
                row {
                    icon(ColorSwatchRenderer.of(fixed))
                    link(ChromaboxBundle.message("contrast.popup.fix", level, ratio)) { onFix(fixed) }
                }
            }
        }.apply { border = JBUI.Borders.empty(8, 12) }
    }

    private fun isFarFrom(content: JComponent, point: Point): Boolean {
        if (!content.isShowing) return false
        val bounds = Rectangle(content.locationOnScreen, content.size)
        val margin = JBUI.scale(MOUSE_MARGIN)
        bounds.grow(margin, margin)
        return !bounds.contains(point)
    }

    private const val MOUSE_MARGIN = 40

    private fun ratio(threshold: Double) = (if (threshold % 1.0 == 0.0) threshold.toInt().toString() else threshold.toString()) + ":1"

    private fun opaque(color: ChromaColor) = Contrast.composite(color, ChromaColor(1.0, 1.0, 1.0))

    /** Sample text in the foreground on the background, normal and large. */
    private class Preview(private val fg: ChromaColor, private val bg: ChromaColor) : JComponent() {
        private val normal get() = UIUtil.getLabelFont()
        private val large get() = normal.deriveFont(Font.BOLD, normal.size2D * 1.5f)

        override fun getPreferredSize(): Dimension {
            val width = maxOf(getFontMetrics(normal).stringWidth(TEXT), getFontMetrics(large).stringWidth(TEXT))
            return Dimension(width + JBUI.scale(16), getFontMetrics(normal).height + getFontMetrics(large).height + JBUI.scale(12))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAntialiasing(g2)
                // A translucent background over white, as the ratio assumes; a translucent foreground over it.
                g2.color = opaque(bg).toAwt()
                g2.fillRect(0, 0, width, height)
                g2.color = BORDER
                g2.draw(Rectangle(0, 0, width - 1, height - 1))
                g2.color = fg.toAwt()
                var y = JBUI.scale(6)
                for (font in listOf(normal, large)) {
                    g2.font = font
                    y += g2.fontMetrics.ascent
                    g2.drawString(TEXT, JBUI.scale(8), y)
                    y += g2.fontMetrics.descent
                }
            } finally {
                g2.dispose()
            }
        }

        private companion object {
            val TEXT get() = ChromaboxBundle.message("contrast.popup.sample")
            val BORDER = JBColor(Color(0, 0, 0, 60), Color(255, 255, 255, 60))
        }
    }
}
