package com.bnsoft.chromabox

import com.bnsoft.chromabox.engine.LensContext
import com.bnsoft.chromabox.engine.LensScanner
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.system.measureNanoTime

/** N-02: a document at the size limit, with every format on, scans in under 150 ms. */
class ScanBenchmarkTest : BasePlatformTestCase() {
    // A line per format, with prose around the colors, so that every lens has text to reject as well as colors.
    private val lines = listOf(
        "The primary color is #1E90FF and the accent is #f00; issue #123 and #bad are hashtags.",
        "Legacy rgb(255, 0, 0) and rgba(255,0,0,.5), modern rgb(255 0 0 / 50%) and hsl(120deg 50% 50%).",
        "Wide gamut oklch(70% 0.1 200), lab(50% 40 59.5) and color(display-p3 1 0 0) in a sentence.",
        "Names like tomato, rebeccapurple and darkred, next to words like reddish, bored and tangent.",
        "val background = Color(0xFF112233) and 0xFFAABBCC, Color.fromARGB(255, 1, 2, 3) in code.",
        "Color awt = new Color(1, 2, 3); UIColor(red: 0.1, green: 0.2, blue: 0.3, alpha: 1) elsewhere.",
        "Tuples [0.1, 0.2, 0.3] and (1.0, 0.5, 0.0, 1.0), but not foo(0.1, 0.2, 0.3) or (1, 2, 3).",
        "A plain line of prose with numbers 1, 2, 3 and words, but no colors at all, to fill the file.",
    )

    fun `test a 2M character document with every format scans in under 150 ms`() {
        val text = buildString {
            var i = 0
            while (length < ChromaboxSettings.MAX_DOCUMENT_LENGTH - 200) append(lines[i++ % lines.size]).append('\n')
        }
        myFixture.configureByText("big.txt", text)
        val document = myFixture.editor.document
        val context = LensContext(PlainTextFileType.INSTANCE, null, document, project)
        val lenses = ChromaboxHost.registeredLenses
        assertEquals(ColorFormat.entries.size, lenses.size)

        fun scan() = runReadAction {
            LensScanner.scan(document.immutableCharSequence, context, lenses).let { ChromaboxHost.withoutShownByIde(it, context) }
        }

        val matches = scan().size
        assertTrue("$matches matches", matches > 50_000)
        repeat(3) { scan() }
        val millis = List(5) { measureNanoTime { scan() } / 1_000_000 }.sorted()
        val median = millis[millis.size / 2]
        println("Scanned ${text.length} characters, $matches matches: median $median ms of $millis")
        assertTrue("median $median ms of $millis", median < 150)
    }
}
