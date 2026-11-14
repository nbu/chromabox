package com.bnsoft.chromabox

import org.junit.Assert.assertEquals
import org.junit.Test

class ContrastPairsTest {
    /** Pairs in [text] as (foreground text, background text), with every hex color in it as a color. */
    private fun pairs(text: String): List<Pair<String, String>> {
        val colors = HexColorParser.findAll(text).map { ColorAt(it.range, com.bnsoft.chromabox.color.ChromaColor.fromAwt(it.color)) }
        return ContrastPairs.find(text, colors).map { text.substring(it.foreground.range) to text.substring(it.background.range) }
    }

    private fun String.substring(range: com.intellij.openapi.util.TextRange) = substring(range.startOffset, range.endOffset)

    @Test
    fun `fg and bg keys in one JSON object`() {
        val json = """
            {
              "button": { "fg": "#ffffff", "border": "#cccccc", "bg": "#0055aa" },
              "link": {
                "foreground": "#0000ee",
                "unrelated": "#123456",
                "background": "#fafafa"
              }
            }
        """.trimIndent()
        assertEquals(listOf("#ffffff" to "#0055aa", "#0000ee" to "#fafafa"), pairs(json))
    }

    @Test
    fun `keys in different blocks don't pair`() {
        val css = "a { color: #111111; }\nb { background-color: #eeeeee; }\n"
        assertEquals(emptyList<Pair<String, String>>(), pairs(css))
        assertEquals(listOf("#111" to "#eee"), pairs("a {\n  color: #111;\n  background-color: #eee;\n}\n"))
    }

    @Test
    fun `YAML blocks by indentation`() {
        val yaml = """
            light:
              text: "#222222"
              surface: "#ffffff"
            dark:
              text: "#eeeeee"
              nested:
                surface: "#000000"
        """.trimIndent()
        assertEquals(listOf("#222222" to "#ffffff"), pairs(yaml))
    }

    @Test
    fun `TOML tables are blocks`() {
        val toml = "[a]\nfg = \"#ffffff\"\n[b]\nbg = \"#000000\"\n"
        assertEquals(emptyList<Pair<String, String>>(), pairs(toml))
        assertEquals(listOf("#ffffff" to "#000000"), pairs("[a]\nfg = \"#ffffff\"\nbg = \"#000000\"\n"))
    }

    @Test
    fun `Material on-X and X, and color-suffixed keys`() {
        assertEquals(listOf("#ffffff" to "#6200ee"), pairs("primary = #6200ee\nonPrimary = #ffffff\n"))
        assertEquals(listOf("#ffffff" to "#6200ee"), pairs(":root {\n  --on-primary: #ffffff;\n  --primary: #6200ee;\n}"))
        assertEquals(listOf("#000000" to "#ffffff"), pairs("{ textColor: #000000, surfaceColor: #ffffff }"))
        // "one" and "only" aren't Material keys.
        assertEquals(emptyList<Pair<String, String>>(), pairs("{\n  online: #ffffff\n}\n{\n  line: #000000\n}"))
    }

    @Test
    fun `two colors on a line pair, otherwise not`() {
        assertEquals(listOf("#ffffff" to "#000000"), pairs("| White on black | #ffffff | #000000 |\n#111 #222 #333\n#444\n"))
    }

    @Test
    fun `a translucent color on a line is the foreground`() {
        assertEquals(listOf("#00000080" to "#ffffff"), pairs("#ffffff #00000080"))
    }

    @Test
    fun `key pairs come first and each color pairs once`() {
        assertEquals(listOf("#ffffff" to "#000000"), pairs("{ fg: #ffffff, bg: #000000, border: #888888 }"))
        assertEquals(listOf("#ffffff" to "#000000"), pairs("{ fg: #ffffff, bg: #000000 }\n{ fg: #111111 }"))
    }
}
