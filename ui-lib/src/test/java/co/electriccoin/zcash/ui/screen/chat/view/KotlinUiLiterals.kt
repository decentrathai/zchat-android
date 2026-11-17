package co.electriccoin.zcash.ui.screen.chat.view

/**
 * ES-A (fix-all 2.13.0, train T4c; Spanish fold spec §3.1): a small Kotlin lexer for the "no English literal on
 * this screen" guards. It is not a line regex:
 * - `//` and `/* */` comments are dropped, while string literals are respected (`"https://…"` survives);
 * - a char literal (`'"'`, `'\''`, `'{'`) is read as a char, so it opens no string and no block;
 * - `"…"` and `"""…"""` literals are read whole; a `${…}` template (which can hold nested strings) and `$name`
 *   become one space in the outer literal, and every string nested inside a template is a literal of its own;
 * - a literal inside the parentheses of a call that is never shown (`Log.x(`, `Timber.`, `throw …(`, `error(`,
 *   `require(`, `check(`, `@Suppress(`) is skipped, with the parentheses matched, so a multi-line `Log.d` is skipped.
 */
internal object KotlinUiLiterals {
    data class Literal(
        val offset: Int,
        val text: String
    )

    /** The lexed source: every literal, and the source with comments, chars and literals blanked (same length). */
    class Lexed(
        val source: String,
        val blanked: String,
        val literals: List<Literal>
    ) {
        fun lineOf(offset: Int): Int = source.substring(0, offset).count { it == '\n' } + 1
    }

    fun lex(src: String): Lexed {
        val lexer = Lexer(src)
        lexer.code(0, inTemplate = false)
        return Lexed(src, lexer.blank.toString(), lexer.literals.sortedBy { it.offset })
    }

    private val nonUiCalls =
        listOf(
            Regex("""\bLog\.[vdiwe]\s*\("""),
            Regex("""\bTimber\.\w+\s*\("""),
            Regex("""\bthrow\s+[\w.]+\s*\("""),
            Regex("""(?<![\w.])error\s*\("""),
            Regex("""(?<![\w.])require\s*\("""),
            Regex("""(?<![\w.])check\s*\("""),
            Regex("""@(?:file:)?Suppress\s*\("""),
        )

    /** The spans (from the call's name to its closing parenthesis) of every call whose text is never shown. */
    fun nonUiSpans(lexed: Lexed): List<IntRange> =
        nonUiCalls.flatMap { re ->
            re.findAll(lexed.blanked).map { m ->
                val open = m.range.last
                m.range.first..matching(lexed.blanked, open, '(', ')')
            }
        }

    /** The offsets [anchor] starts at in code (never in a comment or a string). */
    fun anchorOffsets(
        lexed: Lexed,
        anchor: String
    ): List<Int> = Regex(Regex.escape(anchor)).findAll(lexed.blanked).map { it.range.first }.toList()

    /**
     * The brace-matched block an anchor opens: an anchor ending in `(` is a declaration (its parameter list is
     * matched, then its body); an anchor ending in `{` opens the block itself.
     */
    fun block(
        lexed: Lexed,
        anchor: String
    ): IntRange {
        val at = anchorOffsets(lexed, anchor).singleOrNull() ?: error("anchor not found exactly once: $anchor")
        val b = lexed.blanked
        val open =
            if (anchor.endsWith("(")) {
                val close = matching(b, at + anchor.length - 1, '(', ')')
                b.indexOf('{', close)
            } else {
                at + anchor.length - 1
            }
        check(b[open] == '{') { "no block after anchor $anchor" }
        return at..matching(b, open, '{', '}')
    }

    /** A literal is English UI text when it holds a word of two or more Latin letters and is not allowed. */
    fun isEnglishUiText(
        text: String,
        allowedExact: Set<String>,
        allowedPatterns: List<Regex>
    ): Boolean {
        val trimmed = text.trim()
        if (!Regex("[A-Za-z]{2,}").containsMatchIn(text)) return false
        if (trimmed in allowedExact) return false
        return allowedPatterns.none { it.containsMatchIn(trimmed) }
    }

    /** `line: "literal"` for every English UI literal of [lexed] inside [ranges] (default: the whole file). */
    fun offenders(
        lexed: Lexed,
        allowedExact: Set<String>,
        allowedPatterns: List<Regex>,
        ranges: List<IntRange> = listOf(lexed.source.indices),
        skip: List<IntRange> = emptyList(),
    ): List<String> {
        val nonUi = nonUiSpans(lexed)
        return lexed.literals
            .filter { lit -> ranges.any { lit.offset in it } }
            .filter { lit -> nonUi.none { lit.offset in it } && skip.none { lit.offset in it } }
            .filter { isEnglishUiText(it.text, allowedExact, allowedPatterns) }
            .map { "${lexed.lineOf(it.offset)}: \"${it.text}\"" }
    }

    private fun matching(
        s: String,
        openAt: Int,
        open: Char,
        close: Char
    ): Int {
        var depth = 0
        for (i in openAt until s.length) {
            when (s[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        error("unbalanced $open at $openAt")
    }

    private class Lexer(
        val src: String
    ) {
        val blank = StringBuilder(src)
        val literals = mutableListOf<Literal>()

        private fun wipe(
            from: Int,
            to: Int
        ) {
            for (k in from until minOf(to, src.length)) if (blank[k] != '\n') blank.setCharAt(k, ' ')
        }

        /** Lexes code from [start]; inside a template, stops at the `}` that closes it and returns its index. */
        fun code(
            start: Int,
            inTemplate: Boolean
        ): Int {
            var i = start
            var depth = 0
            while (i < src.length) {
                val c = src[i]
                when {
                    src.startsWith("//", i) -> {
                        val e = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                        wipe(i, e)
                        i = e
                    }
                    src.startsWith("/*", i) -> {
                        val e = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                        wipe(i, e)
                        i = e
                    }
                    c == '\'' -> {
                        val e = if (i + 1 < src.length && src[i + 1] == '\\') src.indexOf('\'', i + 3) + 1 else i + 3
                        wipe(i, e)
                        i = e
                    }
                    src.startsWith("\"\"\"", i) -> i = string(i, raw = true)
                    c == '"' -> i = string(i, raw = false)
                    inTemplate && c == '{' -> {
                        depth++
                        i++
                    }
                    inTemplate && c == '}' -> {
                        if (depth == 0) return i
                        depth--
                        i++
                    }
                    else -> i++
                }
            }
            return i
        }

        private fun string(
            start: Int,
            raw: Boolean
        ): Int {
            val quote = if (raw) 3 else 1
            var j = start + quote
            val body = StringBuilder()
            while (j < src.length) {
                if (raw && src.startsWith("\"\"\"", j)) break
                if (!raw && src[j] == '"') break
                if (!raw && src[j] == '\\') {
                    body.append(src, j, minOf(j + 2, src.length))
                    j += 2
                    continue
                }
                if (src[j] == '$' && j + 1 < src.length && src[j + 1] == '{') {
                    j = code(j + 2, inTemplate = true) + 1
                    body.append(' ')
                    continue
                }
                if (src[j] == '$' && j + 1 < src.length && (src[j + 1].isLetter() || src[j + 1] == '_')) {
                    j++
                    while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                    body.append(' ')
                    continue
                }
                body.append(src[j])
                j++
            }
            literals += Literal(start, body.toString())
            val end = minOf(j + quote, src.length)
            wipe(start, end)
            return end
        }
    }
}
