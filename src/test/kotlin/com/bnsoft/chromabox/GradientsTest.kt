package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradientsTest {
    private fun found(text: String) = Gradients.findAll(text).map { text.substring(it.range.startOffset, it.range.endOffset) }

    // Rounded, so that 0.6000000000000001 is 0.6.
    private fun positions(literal: String) = Gradients.parse(literal)!!.stops.map { Math.round(it.position * 1e9) / 1e9 }

    private fun stopTexts(literal: String) = Gradients.parse(literal)!!.stops.map { it.text }

    @Test
    fun `finds every gradient function, with nested functions, in any case`() {
        val text = "a: linear-gradient(to right, #fff, rgb(0 0 0 / 50%)); b: Repeating-Radial-Gradient(circle, red, blue 20%)" +
            "; c: conic-gradient(from 90deg, red, yellow, green)"
        assertEquals(
            listOf(
                "linear-gradient(to right, #fff, rgb(0 0 0 / 50%))",
                "Repeating-Radial-Gradient(circle, red, blue 20%)",
                "conic-gradient(from 90deg, red, yellow, green)",
            ),
            found(text),
        )
    }

    @Test
    fun `not a gradient`() {
        assertEquals(emptyList<String>(), found("my-linear-gradient(red, blue) linear-gradient(red) linear-gradient(red, blue"))
        assertEquals(emptyList<String>(), found("linear-gradient(var(--a), var(--b))"))
    }

    @Test
    fun `stops in unknown notations are left out, directions and shapes aren't stops`() {
        assertEquals(listOf("#fff", "#000"), stopTexts("linear-gradient(to right, #fff, var(--mid), #000)"))
        assertEquals(listOf("red", "blue"), stopTexts("radial-gradient(circle at center, red, blue)"))
    }

    @Test
    fun `positions are resolved like CSS`() {
        assertEquals(listOf(0.0, 1.0), positions("linear-gradient(red, blue)"))
        assertEquals(listOf(0.0, 0.5, 1.0), positions("linear-gradient(red, green, blue)"))
        assertEquals(listOf(0.2, 0.6, 1.0), positions("linear-gradient(red 20%, green, blue)"))
        assertEquals(listOf(0.0, 0.4, 0.8, 1.0), positions("linear-gradient(red, green, blue 80%, white)"))
        // Two positions make two stops; an earlier position moves up to the one before it.
        assertEquals(listOf(0.1, 0.2, 0.5, 0.5), positions("linear-gradient(red 10% 20%, blue 50%, green 30%)"))
        assertEquals(listOf(0.0, 0.25, 1.0), positions("conic-gradient(red 0deg, blue 90deg, green 1turn)"))
        // Lengths need the box size, so they're treated as missing.
        assertEquals(listOf(0.0, 0.5, 1.0), positions("linear-gradient(red, green 40px, blue)"))
    }

    @Test
    fun `editing one stop rewrites only it, in its notation`() {
        val literal = "linear-gradient(45deg, #FF0000 10%, rgb(0 0 255), red)"
        val gradient = Gradients.parse(literal)!!
        val green = ChromaColor.fromRgb255(0, 128, 0)
        assertEquals("linear-gradient(45deg, #FF0000 10%, rgb(0 128 0), red)", gradient.withColor(1, green).write(gradient))
        assertEquals("linear-gradient(45deg, #008000 10%, rgb(0 0 255), red)", gradient.withColor(0, green).write(gradient))
        assertEquals("linear-gradient(45deg, #FF0000 10%, rgb(0 0 255), green)", gradient.withColor(2, green).write(gradient))
        assertEquals(TextRange(23, 30), gradient.stops[0].range)
    }

    @Test
    fun `transparent renders but isn't editable`() {
        val gradient = Gradients.parse("linear-gradient(transparent, #000)")!!
        assertEquals(0.0, gradient.stops[0].color.alpha, 0.0)
        assertNull(gradient.stops[0].write)
        assertNull(gradient.withColor(0, ChromaColor(1.0, 0.0, 0.0)).write(gradient))
    }

    @Test
    fun `color sequences need three colors with only commas, spaces or arrows between`() {
        val text = "Palette: #111, #222 #333 → #444 -> #555\nTwo: #666 #777\nBroken: #888 and #999, #aaa\n"
        val colors = HexColorParser.findAll(text).map { ColorAt(it.range, ChromaColor.fromAwt(it.color)) }
        val sequences = ColorSequences.find(text, colors).map { seq -> seq.map { text.substring(it.range.startOffset, it.range.endOffset) } }
        assertEquals(listOf(listOf("#111", "#222", "#333", "#444", "#555")), sequences)
    }
}
