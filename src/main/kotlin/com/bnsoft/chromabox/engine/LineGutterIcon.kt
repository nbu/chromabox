package com.bnsoft.chromabox.engine

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseEvent
import javax.swing.Icon

/** One entry of a [LineGutterIcon]: a match's icon, its text, and what clicking it does. */
internal class GutterEntry(val icon: Icon?, val text: String, val pick: (RelativePoint) -> Unit)

/**
 * The gutter icon of one line in [LensPresentation.GUTTER] (C-06). One entry shows its icon; two to four are split
 * into vertical segments; more show four segments and a "+". Clicking picks the entry, after a list popup if there
 * are several.
 */
internal class LineGutterIcon(private val entries: List<GutterEntry>) : GutterIconRenderer() {
    private val icon = SegmentedIcon(entries.mapNotNull { it.icon })

    override fun getIcon(): Icon = icon

    override fun getTooltipText(): String = entries.joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it.text) }

    override fun isNavigateAction() = true

    override fun getClickAction(): AnAction = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val mouse = e.inputEvent as? MouseEvent ?: return
            val location = RelativePoint(mouse)
            val single = entries.singleOrNull()
            if (single != null) {
                single.pick(location)
                return
            }
            val step = object : BaseListPopupStep<GutterEntry>(null, entries) {
                override fun getTextFor(value: GutterEntry) = value.text

                override fun getIconFor(value: GutterEntry) = value.icon

                override fun onChosen(selectedValue: GutterEntry, finalChoice: Boolean): PopupStep<*>? =
                    doFinalStep { selectedValue.pick(location) }
            }
            JBPopupFactory.getInstance().createListPopup(step).show(location)
        }
    }

    // Renderers are compared to decide whether a highlighter's icon changed; each line's renderer is created anew.
    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)

    internal val segmentCount get() = icon.segments.size
    internal val hasMore get() = icon.more
}

/** Up to [MAX_SEGMENTS] icons side by side, each clipped to its share of the width, and a "+" if there are more. */
internal class SegmentedIcon(icons: List<Icon>) : Icon {
    val segments = icons.take(MAX_SEGMENTS)
    val more = icons.size > MAX_SEGMENTS

    private val size get() = segments.maxOfOrNull { maxOf(it.iconWidth, it.iconHeight) } ?: JBUI.scale(12)

    override fun getIconWidth() = size + if (more) JBUI.scale(PLUS_WIDTH) else 0

    override fun getIconHeight() = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val size = size
        for ((i, icon) in segments.withIndex()) {
            val g2 = g.create() as Graphics2D
            try {
                val from = x + size * i / segments.size
                val to = x + size * (i + 1) / segments.size
                g2.clipRect(from, y, to - from, size)
                icon.paintIcon(c, g2, x + (size - icon.iconWidth) / 2, y + (size - icon.iconHeight) / 2)
            } finally {
                g2.dispose()
            }
        }
        if (more) paintPlus(g, x + size, y, size)
    }

    private fun paintPlus(g: Graphics, x: Int, y: Int, size: Int) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            g2.color = JBColor.foreground()
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
            val arm = JBUI.scale(PLUS_WIDTH - 2) / 2
            val cx = x + JBUI.scale(PLUS_WIDTH) / 2
            val cy = y + size / 2
            g2.drawLine(cx - arm, cy, cx + arm, cy)
            g2.drawLine(cx, cy - arm, cx, cy + arm)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val MAX_SEGMENTS = 4
        const val PLUS_WIDTH = 7
    }
}
