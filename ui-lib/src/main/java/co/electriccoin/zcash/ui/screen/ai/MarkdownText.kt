package co.electriccoin.zcash.ui.screen.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight Markdown renderer for AI replies — intentionally NOT a full CommonMark engine (no
 * extra dependency). Handles what LLMs actually emit: fenced ```code``` blocks (monospace, scrollable,
 * with a copy button), inline `code`, **bold**, *italic*, `#`/`##`/`###` headings and `-`/`*`/`N.`
 * bullet lists. Anything else renders as plain text. Keeps the paid output legible instead of showing
 * raw ``` and ** noise.
 */
@Composable
fun MarkdownText(
    text: String,
    textColor: Color,
    codeColor: Color,
    codeBackground: Color,
    accent: Color,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    Column(modifier = modifier) {
        parseBlocks(text).forEach { block ->
            if (block.isCode) {
                CodeBlock(block.text, codeColor, codeBackground, accent, onCopyCode)
            } else {
                block.text.split("\n").forEach { line ->
                    ProseLine(line, textColor, codeColor, fontSize)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, codeColor: Color, codeBackground: Color, accent: Color, onCopyCode: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(codeBackground)
            .padding(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { onCopyCode(code) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Share, contentDescription = "Copy code", tint = accent, modifier = Modifier.size(15.dp))
            }
        }
        Text(
            text = code,
            color = codeColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun ProseLine(line: String, textColor: Color, codeColor: Color, fontSize: androidx.compose.ui.unit.TextUnit) {
    val trimmed = line.trimStart()
    when {
        trimmed.startsWith("### ") ->
            Text(inlineAnnotated(trimmed.removePrefix("### "), codeColor), color = textColor, fontSize = (fontSize.value + 1).sp, fontWeight = FontWeight.Bold)
        trimmed.startsWith("## ") ->
            Text(inlineAnnotated(trimmed.removePrefix("## "), codeColor), color = textColor, fontSize = (fontSize.value + 2).sp, fontWeight = FontWeight.Bold)
        trimmed.startsWith("# ") ->
            Text(inlineAnnotated(trimmed.removePrefix("# "), codeColor), color = textColor, fontSize = (fontSize.value + 4).sp, fontWeight = FontWeight.Bold)
        trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
            Row {
                Text("•  ", color = textColor, fontSize = fontSize)
                Text(inlineAnnotated(trimmed.drop(2), codeColor), color = textColor, fontSize = fontSize)
            }
        Regex("^\\d+\\. ").containsMatchIn(trimmed) -> {
            val num = trimmed.substringBefore(". ")
            Row {
                Text("$num. ", color = textColor, fontSize = fontSize, fontWeight = FontWeight.Medium)
                Text(inlineAnnotated(trimmed.substringAfter(". "), codeColor), color = textColor, fontSize = fontSize)
            }
        }
        else -> Text(inlineAnnotated(line, codeColor), color = textColor, fontSize = fontSize)
    }
}

private data class MdBlock(val isCode: Boolean, val text: String)

private const val MAX_MARKDOWN_LEN = 200_000

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    // Defensive cap: the source is an (untrusted) AI reply. A pathological multi-MB reply would build
    // multi-MB StringBuilders below and could OOM the render. 200K chars is far more than any genuine
    // chat answer; truncate the rest rather than risk exhaustion.
    val capped = if (src.length > MAX_MARKDOWN_LEN) src.take(MAX_MARKDOWN_LEN) else src
    val prose = StringBuilder()
    val code = StringBuilder()
    var inCode = false
    for (line in capped.split("\n")) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out.add(MdBlock(true, code.toString().trimEnd('\n')))
                code.clear(); inCode = false
            } else {
                if (prose.isNotEmpty()) { out.add(MdBlock(false, prose.toString().trimEnd('\n'))); prose.clear() }
                inCode = true
            }
        } else if (inCode) {
            code.append(line).append("\n")
        } else {
            prose.append(line).append("\n")
        }
    }
    // Unterminated fence → treat collected code as a code block so nothing is lost.
    if (inCode && code.isNotEmpty()) out.add(MdBlock(true, code.toString().trimEnd('\n')))
    if (prose.isNotEmpty()) out.add(MdBlock(false, prose.toString().trimEnd('\n')))
    return out
}

/** Inline spans: **bold**, *italic*, `code`. Unmatched markers render literally. */
private fun inlineAnnotated(text: String, codeColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
