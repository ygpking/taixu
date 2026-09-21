package top.wkbin.taixu.ui.chat

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import top.wkbin.taixu.harness.MentionExtractor

/** 构建精准匹配技能与插件实体的正则表达式（优先长词带空格全称匹配） */
internal fun buildMentionRegex(knownNames: List<String>): Regex {
    val sorted = knownNames.filter { it.isNotBlank() }.sortedByDescending { it.length }
    val escaped = sorted.map { Regex.escape(it) }
    val generic = """[^${MentionExtractor.MENTION_HARD_BOUNDARY}]+"""
    // 与 MentionExtractor 同一套：已知名单最长优先 + 邮箱/词边界保护 + 硬终止符。
    // 终止符直接复用后端常量，不再各写一份——两处不一致时会出现
    // 「UI 高亮了、后端没解析」的静默漂移。
    //
    // 尾部边界断言同样必须与后端第一轮一致：少了它，已知名 "Git" 会吞掉 "@GitHub"
    // 的前缀（UI 高亮成 Git 提及），而后端因尾部不是终止符不匹配、改按通用轮解析成
    // "github"——用户看到的高亮与实际生效的技能不是同一个。
    // 对通用分支无害：`[^boundary]+` 本来就只能停在边界或行尾，断言恒真。
    val body = if (escaped.isNotEmpty()) {
        """${escaped.joinToString("|")}|$generic"""
    } else {
        generic
    }
    return Regex("""(?<![\w.+-])@($body)(?=$|[${MentionExtractor.MENTION_HARD_BOUNDARY}])""")
}

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

