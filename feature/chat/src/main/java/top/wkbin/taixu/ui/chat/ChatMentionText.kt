package top.wkbin.taixu.ui.chat

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** 构建精准匹配技能与插件实体的正则表达式（优先长词带空格全称匹配） */

internal fun buildMentionRegex(knownNames: List<String>): Regex {
    val sorted = knownNames.filter { it.isNotBlank() }.sortedByDescending { it.length }
    val escaped = sorted.map { Regex.escape(it) }
    val pattern = if (escaped.isNotEmpty()) {
        """@(${escaped.joinToString("|")}|[^$MENTION_HARD_BOUNDARY]+)"""
    } else {
        """@([^$MENTION_HARD_BOUNDARY]+)"""
    }
    return Regex(pattern)
}

/**
 * 与后端 top.wkbin.taixu.harness.MentionExtractor 保持同一套终止符口径，
 * 避免"UI 高亮了、后端却没解析出来"的两套标准（曾因 UI 支持空格全名而后端不支持，
 * 导致「Git 敏捷工作流」这类技能静默失效）。
 * 注意 `[` `]` 必须转义——未转义的 `]` 会提前终结正则字符类。
 */
internal const val MENTION_HARD_BOUNDARY =
    "\\s@,，:：;；!！?？。、()（）\\[\\]【】{}<>《》\"'“”‘’`|/\\\\"

/** 为文本中的 @能力 实体添加自适应半透明高亮样式（支持带空格全称） */
internal fun formatMentionText(
    text: String,
    knownNames: List<String>,
    mentionColor: Color,
    mentionBg: Color,
): AnnotatedString {
    if (!text.contains("@")) return AnnotatedString(text)
    val builder = AnnotatedString.Builder(text)
    val regex = buildMentionRegex(knownNames)
    for (match in regex.findAll(text)) {
        val range = match.range
        builder.addStyle(
            SpanStyle(
                color = mentionColor,
                fontWeight = FontWeight.SemiBold,
                background = mentionBg,
            ),
            range.first,
            range.last + 1,
        )
    }
    return builder.toAnnotatedString()
}

/**
 * 🌟 输入框内 @能力 实体富文本语法高亮变换器
 * 将 `@xxx` 自动渲染为优雅的主题色半透明胶囊样式（对齐 Telegram / 微信 / Discord 设计，支持带空格全称）
 */
internal class MentionVisualTransformation(
    private val knownNames: List<String>,
    private val mentionColor: Color,
    private val mentionBg: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val transformed = formatMentionText(text.text, knownNames, mentionColor, mentionBg)
        return TransformedText(transformed, OffsetMapping.Identity)
    }
}

