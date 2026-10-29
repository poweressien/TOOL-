package com.toolkit.privacy

data class FontStyle(val name: String, val group: String, val transform: (String) -> String)

/** Turns plain text into fancy Unicode "fonts" (they are real characters, so they paste anywhere: bios, captions, chats). */
object FontStyles {
    private fun cps(s: String) = s.codePoints().toArray().toList()
    private fun build(parts: List<Int>): String {
        val sb = StringBuilder(); parts.forEach { sb.appendCodePoint(it) }; return sb.toString()
    }

    /** Mathematical alphanumeric style: contiguous blocks with a few "holes" that live elsewhere in Unicode. */
    private fun math(upper: Int, lower: Int, digit: Int?, holes: Map<Char, Int> = emptyMap()): (String) -> String = { s ->
        build(cps(s).map { c ->
            val ch = c.toChar()
            when {
                c > 0xFFFF -> c
                holes.containsKey(ch) -> holes.getValue(ch)
                ch in 'A'..'Z' -> upper + (ch - 'A')
                ch in 'a'..'z' -> lower + (ch - 'a')
                ch in '0'..'9' && digit != null -> digit + (ch - '0')
                else -> c
            }
        })
    }

    private fun mapped(pairs: Map<Char, String>, upperToo: Boolean = true): (String) -> String = { s ->
        val sb = StringBuilder()
        s.forEach { ch ->
            sb.append(pairs[ch] ?: (if (upperToo) (pairs[ch.lowercaseChar()] ?: pairs[ch.uppercaseChar()]) else null) ?: ch.toString())
        }
        sb.toString()
    }

    private fun strings(from: String, to: List<String>): Map<Char, String> = from.indices.associate { from[it] to to[it] }
    private fun letters(lowerTo: String): Map<Char, String> = strings("abcdefghijklmnopqrstuvwxyz", lowerTo.map { it.toString() })

    private fun combine(mark: Char, skipSpaces: Boolean = true): (String) -> String = { s ->
        val sb = StringBuilder()
        s.forEach { ch -> sb.append(ch); if (!(skipSpaces && ch == ' ')) sb.append(mark) }
        sb.toString()
    }

    private fun wrap(left: String, right: String): (String) -> String = { s -> "$left$s$right" }

    private val SMALL_CAPS = letters("ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ")
    private val SUPER = letters("ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖᵠʳˢᵗᵘᵛʷˣʸᶻ") + "0123456789".indices.associate { "0123456789"[it] to "⁰¹²³⁴⁵⁶⁷⁸⁹"[it].toString() }
    private val SUB: Map<Char, String> = mapOf(
        'a' to "ₐ", 'e' to "ₑ", 'h' to "ₕ", 'i' to "ᵢ", 'j' to "ⱼ", 'k' to "ₖ", 'l' to "ₗ", 'm' to "ₘ", 'n' to "ₙ",
        'o' to "ₒ", 'p' to "ₚ", 'r' to "ᵣ", 's' to "ₛ", 't' to "ₜ", 'u' to "ᵤ", 'v' to "ᵥ", 'x' to "ₓ"
    ) + "0123456789".indices.associate { "0123456789"[it] to "₀₁₂₃₄₅₆₇₈₉"[it].toString() }

    private val FLIP_LOWER = letters("ɐqɔpǝɟƃɥᴉɾʞlɯuodbɹsʇnʌʍxʎz")
    private val FLIP_UPPER = strings("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "∀ᗺƆᗡƎℲ⅁HIſʞ˥WNOԀΌᴚS⊥∩ΛMX⅄Z".map { it.toString() })
    private val FLIP_OTHER = mapOf(
        '0' to "0", '1' to "Ɩ", '2' to "ᄅ", '3' to "Ɛ", '4' to "ㄣ", '5' to "ϛ", '6' to "9", '7' to "ㄥ", '8' to "8", '9' to "6",
        '.' to "˙", ',' to "'", '\'' to ",", '?' to "¿", '!' to "¡", '(' to ")", ')' to "(", '[' to "]", ']' to "[", '<' to ">", '>' to "<", '_' to "‾"
    )

    private val CANADIAN = strings("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "ᗩᗷᑕᗪEᖴGᕼIᒍKᒪᗰᑎOᑭᑫᖇᔕTᑌᐯᗯ᙭Yᘔ".map { it.toString() })
    private val LEET = mapOf('a' to "4", 'b' to "8", 'e' to "3", 'g' to "6", 'i' to "1", 'l' to "1", 'o' to "0", 's' to "5", 't' to "7", 'z' to "2")
    private val ASIAN = strings("abcdefghijklmnopqrstuvwxyz", listOf("ﾑ", "乃", "ᄃ", "り", "乇", "ｷ", "ム", "ん", "ﾉ", "ﾌ", "ズ", "ﾚ", "ﾶ", "刀", "の", "ｱ", "ゐ", "尺", "丂", "ｲ", "ひ", "√", "W", "ﾒ", "ﾘ", "乙"))

    private val GLITCH_MARKS = listOf('\u0300', '\u0301', '\u0302', '\u0303', '\u0304', '\u0306', '\u0307', '\u0308', '\u030A', '\u030B', '\u030C',
        '\u0316', '\u0317', '\u0323', '\u0324', '\u0325', '\u0328', '\u032D', '\u0330', '\u0331', '\u0338')

    private val glitch: (String) -> String = { s ->
        val sb = StringBuilder()
        var seed = 7
        s.forEach { ch ->
            sb.append(ch)
            if (ch != ' ') repeat(3) { seed = (seed * 31 + 17) % 9973; sb.append(GLITCH_MARKS[seed % GLITCH_MARKS.size]) }
        }
        sb.toString()
    }

    private val alternating: (String) -> String = { s ->
        var i = 0
        s.map { c -> if (c.isLetter()) { val r = if (i % 2 == 0) c.lowercaseChar() else c.uppercaseChar(); i++; r } else c }.joinToString("")
    }

    private val BOLD_SCRIPT = math(0x1D4D0, 0x1D4EA, null)

    val all: List<FontStyle> = listOf(
        FontStyle("Bold", "Classic", math(0x1D400, 0x1D41A, 0x1D7CE)),
        FontStyle("Italic", "Classic", math(0x1D434, 0x1D44E, null, mapOf('h' to 0x210E))),
        FontStyle("Bold Italic", "Classic", math(0x1D468, 0x1D482, null)),
        FontStyle("Sans", "Classic", math(0x1D5A0, 0x1D5BA, 0x1D7E2)),
        FontStyle("Sans Bold", "Classic", math(0x1D5D4, 0x1D5EE, 0x1D7EC)),
        FontStyle("Sans Italic", "Classic", math(0x1D608, 0x1D622, 0x1D7E2)),
        FontStyle("Sans Bold Italic", "Classic", math(0x1D63C, 0x1D656, 0x1D7EC)),
        FontStyle("Monospace", "Classic", math(0x1D670, 0x1D68A, 0x1D7F6)),

        FontStyle("Script", "Fancy", math(0x1D49C, 0x1D4B6, null, mapOf(
            'B' to 0x212C, 'E' to 0x2130, 'F' to 0x2131, 'H' to 0x210B, 'I' to 0x2110, 'L' to 0x2112, 'M' to 0x2133, 'R' to 0x211B,
            'e' to 0x212F, 'g' to 0x210A, 'o' to 0x2134))),
        FontStyle("Bold Script", "Fancy", math(0x1D4D0, 0x1D4EA, null)),
        FontStyle("Fraktur", "Fancy", math(0x1D504, 0x1D51E, null, mapOf('C' to 0x212D, 'H' to 0x210C, 'I' to 0x2111, 'R' to 0x211C, 'Z' to 0x2128))),
        FontStyle("Bold Fraktur", "Fancy", math(0x1D56C, 0x1D586, null)),
        FontStyle("Double Struck", "Fancy", math(0x1D538, 0x1D552, 0x1D7D8, mapOf(
            'C' to 0x2102, 'H' to 0x210D, 'N' to 0x2115, 'P' to 0x2119, 'Q' to 0x211A, 'R' to 0x211D, 'Z' to 0x2124))),
        FontStyle("Small Caps", "Fancy", mapped(SMALL_CAPS) ),
        FontStyle("Fullwidth", "Fancy", { s -> build(cps(s).map { c -> when {
            c == ' '.code -> 0x3000
            c in 0x21..0x7E -> c + 0xFEE0
            else -> c } }) }),
        FontStyle("Canadian", "Fancy", mapped(CANADIAN)),
        FontStyle("Katakana", "Fancy", mapped(ASIAN)),

        FontStyle("Circled", "Shapes", { s -> build(cps(s).map { c -> when {
            c in 'A'.code..'Z'.code -> 0x24B6 + (c - 'A'.code)
            c in 'a'.code..'z'.code -> 0x24D0 + (c - 'a'.code)
            c == '0'.code -> 0x24EA
            c in '1'.code..'9'.code -> 0x2460 + (c - '1'.code)
            else -> c } }) }),
        FontStyle("Black Circles", "Shapes", { s -> build(cps(s).map { c -> when {
            c in 'A'.code..'Z'.code -> 0x1F150 + (c - 'A'.code)
            c in 'a'.code..'z'.code -> 0x1F150 + (c - 'a'.code)
            else -> c } }) }),
        FontStyle("Squares", "Shapes", { s -> build(cps(s).map { c -> when {
            c in 'A'.code..'Z'.code -> 0x1F130 + (c - 'A'.code)
            c in 'a'.code..'z'.code -> 0x1F130 + (c - 'a'.code)
            else -> c } }) }),
        FontStyle("Black Squares", "Shapes", { s -> build(cps(s).map { c -> when {
            c in 'A'.code..'Z'.code -> 0x1F170 + (c - 'A'.code)
            c in 'a'.code..'z'.code -> 0x1F170 + (c - 'a'.code)
            else -> c } }) }),
        FontStyle("Parenthesized", "Shapes", { s -> build(cps(s).map { c -> when {
            c in 'A'.code..'Z'.code -> 0x249C + (c - 'A'.code)
            c in 'a'.code..'z'.code -> 0x249C + (c - 'a'.code)
            c in '1'.code..'9'.code -> 0x2474 + (c - '1'.code)
            else -> c } }) }),

        FontStyle("Superscript", "Tiny", mapped(SUPER)),
        FontStyle("Subscript", "Tiny", mapped(SUB)),
        FontStyle("Upside Down", "Fun", { s ->
            val sb = StringBuilder()
            s.reversed().forEach { ch ->
                sb.append(FLIP_UPPER[ch] ?: FLIP_LOWER[ch] ?: FLIP_LOWER[ch.lowercaseChar()] ?: FLIP_OTHER[ch] ?: ch.toString())
            }
            sb.toString() }),
        FontStyle("Leet", "Fun", { s -> s.map { LEET[it.lowercaseChar()] ?: it.toString() }.joinToString("") }),
        FontStyle("aLtErNaTiNg", "Fun", alternating),
        FontStyle("Spaced Out", "Fun", { s -> s.toCharArray().joinToString(" ") }),
        FontStyle("Dotted", "Fun", { s -> s.toCharArray().joinToString("•") }),
        FontStyle("Glitch", "Fun", glitch),

        FontStyle("Strikethrough", "Lines", combine('\u0336')),
        FontStyle("Underline", "Lines", combine('\u0332')),
        FontStyle("Double Underline", "Lines", combine('\u0333')),
        FontStyle("Overline", "Lines", combine('\u0305')),
        FontStyle("Slash Through", "Lines", combine('\u0338')),
        FontStyle("Wavy Below", "Lines", combine('\u0330')),
        FontStyle("Crossed Out", "Lines", combine('\u0335')),

        FontStyle("Stars", "Decor", wrap("★彡 ", " 彡★")),
        FontStyle("Sparkles", "Decor", wrap("✨ ", " ✨")),
        FontStyle("Fancy Brackets", "Decor", wrap("【", "】")),
        FontStyle("Corner Quotes", "Decor", wrap("『", "』")),
        FontStyle("Hearts", "Decor", wrap("♡ ", " ♡")),
        FontStyle("Wings", "Decor", wrap("꧁ ", " ꧂")),
        FontStyle("Arrows", "Decor", wrap("»»— ", " —««")),
        FontStyle("Flowers", "Decor", wrap("✿ ", " ✿")),
        FontStyle("Bold + Sparkle", "Decor", { s -> "✧ " + math(0x1D400, 0x1D41A, 0x1D7CE)(s) + " ✧" }),
        FontStyle("Script + Hearts", "Decor", { s -> "♡ " + BOLD_SCRIPT(s) + " ♡" })
    )

    val groups: List<String> get() = all.map { it.group }.distinct()
}
