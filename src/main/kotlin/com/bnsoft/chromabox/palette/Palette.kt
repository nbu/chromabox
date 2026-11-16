package com.bnsoft.chromabox.palette

import com.bnsoft.chromabox.ChromaboxHost
import com.bnsoft.chromabox.ChromaboxSettings
import com.bnsoft.chromabox.ColorFormat
import com.bnsoft.chromabox.Gradient
import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.color.ColorSpaces
import com.bnsoft.chromabox.color.DeltaE
import com.bnsoft.chromabox.engine.Lens
import com.bnsoft.chromabox.engine.LensContext
import com.bnsoft.chromabox.engine.LensMatch
import com.bnsoft.chromabox.engine.LensScanner
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import java.util.Locale

/**
 * One place a color is written: at [range] in [file], as [text]. From a scan, [write] gives the text for another color
 * in the same notation, or null if that notation can't express it. From the index, [text] and [write] are null; the
 * file is scanned again before it's written.
 */
class PaletteOccurrence(
    val file: VirtualFile,
    val range: TextRange,
    val color: ChromaColor,
    val format: ColorFormat?,
    val text: String? = null,
    val write: ((ChromaColor) -> String?)? = null,
)

/** A color and every place it's written (C-03.3); colors that are equal in 8-bit sRGBA are one entry. */
class PaletteEntry(val color: ChromaColor, val occurrences: List<PaletteOccurrence>) {
    val hex: String get() = Palette.hex(color)

    val formats: List<ColorFormat> get() = occurrences.mapNotNull { it.format }.distinct().sorted()
}

/**
 * Colors that look nearly the same (C-03.4): [leader], the most used, and [members] that differ from it by less than
 * the threshold, nearest first, with their CIEDE2000 [distances] from it.
 */
class PaletteGroup(val leader: PaletteEntry, val members: List<PaletteEntry>, val distances: List<Double>) {
    val entries: List<PaletteEntry> get() = listOf(leader) + members
}

enum class PaletteSort { USAGE, HUE, LIGHTNESS }

object Palette {
    /** The colors in [document], with gradient stops as colors of their own. Needs a read action. */
    fun scan(file: VirtualFile, document: Document, project: Project?): List<PaletteOccurrence> {
        val settings = ChromaboxSettings.getInstance()
        val fileType = file.fileType
        if (document.textLength > ChromaboxSettings.MAX_DOCUMENT_LENGTH || settings.isExcluded(fileType)) return emptyList()
        val context = LensContext(fileType, (fileType as? LanguageFileType)?.language, document, project)
        val text = document.immutableCharSequence
        return LensScanner.scan(text, context, ChromaboxHost.lenses).flatMap { scanned ->
            val format = ColorFormat.byLensId(scanned.lens.id)
            when (val value = scanned.match.value) {
                is ChromaColor -> {
                    @Suppress("UNCHECKED_CAST")
                    val write = writer(scanned.lens as Lens<ChromaColor>, scanned.match as LensMatch<ChromaColor>)
                    listOf(PaletteOccurrence(file, scanned.range, value, format, text.substring(scanned.range), write))
                }
                is Gradient -> value.stops.map { stop ->
                    val range = stop.range.shiftRight(scanned.range.startOffset)
                    PaletteOccurrence(file, range, stop.color, format, stop.text, stop.write)
                }
                else -> emptyList()
            }
        }
    }

    private fun writer(lens: Lens<ChromaColor>, match: LensMatch<ChromaColor>): ((ChromaColor) -> String?)? {
        val editor = lens.editor(match) ?: return null
        return { color -> editor.format(color, match) }
    }

    private fun CharSequence.substring(range: TextRange) = subSequence(range.startOffset, range.endOffset).toString()

    /** Groups [occurrences] by color, most used first. */
    fun entries(occurrences: List<PaletteOccurrence>): List<PaletteEntry> =
        occurrences.groupBy { hex(it.color) }.values
            .map { PaletteEntry(ChromaColor.fromAwt(it.first().color.toAwt()), it) }
            .let { sorted(it, PaletteSort.USAGE) }

    fun sorted(entries: List<PaletteEntry>, sort: PaletteSort): List<PaletteEntry> = when (sort) {
        PaletteSort.USAGE -> entries.sortedWith(compareByDescending<PaletteEntry> { it.occurrences.size }.thenBy { it.hex })
        // Grays last, dark to light; colors by hue, starting at red.
        PaletteSort.HUE -> entries.sortedWith(compareBy<PaletteEntry>({ hue(it.color) }, { lightness(it.color) }, { it.hex }))
        PaletteSort.LIGHTNESS -> entries.sortedWith(compareBy<PaletteEntry>({ lightness(it.color) }, { it.hex }))
    }

    /**
     * Groups near-duplicates (C-03.4): the most used color not yet in a group leads one, with every other such color
     * within [threshold] of it. Distances are from the leader, so a group doesn't drift through a chain of colors each
     * close to the next. Colors whose alpha differs more than a little aren't grouped.
     */
    fun groups(entries: List<PaletteEntry>, threshold: Double): List<PaletteGroup> {
        val remaining = sorted(entries, PaletteSort.USAGE).map { it to ColorSpaces.toLab(it.color) }.toMutableList()
        val groups = ArrayList<PaletteGroup>()
        while (remaining.isNotEmpty()) {
            val (leader, lab) = remaining.removeAt(0)
            val near = remaining.mapNotNull { (entry, other) ->
                if (kotlin.math.abs(entry.color.alpha - leader.color.alpha) > ALPHA_TOLERANCE) return@mapNotNull null
                val distance = DeltaE.ciede2000(lab, other)
                if (distance < threshold) entry to distance else null
            }.sortedBy { it.second }
            val members = near.map { it.first }.toSet()
            remaining.removeAll { it.first in members }
            groups += PaletteGroup(leader, near.map { it.first }, near.map { it.second })
        }
        return groups
    }

    /** [groups] in the order [sort] puts their leaders in. */
    fun sortedGroups(groups: List<PaletteGroup>, sort: PaletteSort): List<PaletteGroup> {
        val byLeader = groups.associateBy { it.leader }
        return sorted(groups.map { it.leader }, sort).map { byLeader.getValue(it) }
    }

    // Alpha difference under which two colors can still be near-duplicates: about 5 in 8 bits.
    private const val ALPHA_TOLERANCE = 0.02

    private fun hue(color: ChromaColor): Double {
        val oklch = ColorSpaces.toOklch(color)
        return if (oklch.h.isNaN() || oklch.c < GRAY_CHROMA) Double.MAX_VALUE else oklch.h
    }

    private fun lightness(color: ChromaColor) = ColorSpaces.toOklch(color).l

    // OKLCH chroma under which a color counts as a gray when sorting by hue.
    private const val GRAY_CHROMA = 0.02

    /** `#rrggbb`, or `#rrggbbaa` if translucent, of the color clamped to 8-bit sRGB. */
    fun hex(color: ChromaColor): String {
        val awt = color.toAwt()
        val rgb = String.format(Locale.ROOT, "#%02x%02x%02x", awt.red, awt.green, awt.blue)
        return if (awt.alpha == 255) rgb else rgb + String.format(Locale.ROOT, "%02x", awt.alpha)
    }
}
