package top.wkbin.taixu.harness

import java.text.Normalizer

/**
 * 解析用户消息中的 @提及 名称（技能 / MCP 服务 / 子智能体触发）。
 *
 * ## 为什么需要 [knownNames]
 * 早期实现只用 `@([^\s@,，:：\n]+)` 这种"空白/少数标点即断"的正则，对**含空格的技能名**完全失效：
 * `@Git 敏捷工作流` 只会解析出 `Git`，导致该技能静默不生效（而 UI 侧因支持全名高亮，用户以为已识别）。
 * 传入 [knownNames] 后启用**已知名单最长优先匹配**，可正确吞掉技能名内部的空格。
 *
 * ## 归一化
 * 统一做 NFKC 折半角（全角 `＠`/字母/数字 → 半角）后再匹配，避免 `@Ｇｉｔ` 这类全角输入失配。
 * 返回的名称一律 lowercase，便于与技能 id / name / triggerCommand 做大小写无关比较。
 */
object MentionExtractor {

    /**
     * 显式终止符：中英文标点、括号、引号、命令分隔等，出现即截断。
     * 注意 `[` `]` 必须转义——未转义的 `]` 会提前终结正则字符类，使后续终止符全部失效。
     *
     * 公开给聊天 UI（`ChatMentionText.buildMentionRegex`）复用同一字符类，
     * 避免「UI 高亮了、后端却没解析」的口径漂移——两处各写一份时，
     * 任一侧增删终止符都会造成静默不一致。
     */
    const val MENTION_HARD_BOUNDARY =
        "\\s@,，:：;；!！?？。、()（）\\[\\]【】{}<>《》\"'“”‘’`|/\\\\"

    private val GENERIC_REGEX = Regex("""@([^$MENTION_HARD_BOUNDARY]+)""")

    /**
     * @param text 用户原始消息
     * @param knownNames 已知实体名（技能 name / id / triggerCommand 去掉前导 `/`、MCP 服务名等）。
     *                  传入后启用最长优先匹配，可正确处理名称内含空格的情况；传空则退化为通用解析。
     */
    fun parse(text: String, knownNames: Collection<String> = emptyList()): Set<String> {
        if (!text.contains("@") && !text.contains("＠")) return emptySet()
        val normalized = normalize(text)
        val result = linkedSetOf<String>()

        // 用可变字符数组「擦除」已识别的片段，避免重叠匹配与二次拆分。
        val buffer = StringBuilder(normalized)

        // 第一轮：已知名单最长优先匹配（解决名称含空格 / 与正文边界难分的问题）。
        val candidates = knownNames
            .asSequence()
            .filter { it.isNotBlank() }
            .map { it.removePrefix("/").trim() }
            .filter { it.isNotBlank() }
            .map { normalize(it).lowercase() }
            .distinct()
            .sortedByDescending { it.length }
            .toList()

        for (candidate in candidates) {
            // 边界保护：@ 之后必须是「终止符或文本结束」，避免 "Git" 误吞 "GitHub" 的前缀；
            // @ 之前必须不是词字符，避免邮箱 user@host.com 里的 @host 被当成提及。
            val pattern = Regex(
                """(?<![\w.+-])@${Regex.escape(candidate)}(?=$|[$MENTION_HARD_BOUNDARY])""",
                RegexOption.IGNORE_CASE,
            )
            var match = pattern.find(buffer)
            var guard = 0
            while (match != null && guard++ < MAX_MENTIONS_PER_MESSAGE) {
                val start = match.range.first
                val end = match.range.last + 1
                // 擦除：整段替换为等长空格，防止通用轮把这些字符再拆出来。
                for (i in start until end) buffer.setCharAt(i, ' ')
                result += candidate
                match = pattern.find(buffer)
            }
        }

        // 第二轮：通用兜底（覆盖未登记的实体），此时剩余的 @xxx 都不会再被误拆。
        for (match in GENERIC_REGEX.findAll(buffer.toString())) {
            // 邮箱/标识符保护：@ 前一个字符是**ASCII 词字符**时不视为提及
            //（如 user@host.com）。
            //
            // 判定必须与第一轮的 lookbehind `(?<![\w.+-])` 同一口径（\w 仅 ASCII）。
            // 这里原先是 `Char.isLetterOrDigit()`（Unicode）：同一个「请用@未知技能」，
            // 第一轮能命中已知名、第二轮却因前一字是中文而静默丢弃——同一输入两轮结论相反。
            val atIndex = match.range.first
            if (atIndex > 0 && isAsciiWordChar(buffer[atIndex - 1])) continue
            val name = match.groupValues[1].trim()
            if (name.isEmpty()) continue
            // 域名/邮箱尾保护：「用户@host.com」里 @ 前是中文（非 ASCII 词字符），
            // 前面的守卫放它过去，于是 host.com 会被当成未登记实体收进来，
            // 再被系统提示的「未匹配 @提及」段告知模型"这个技能名拼错了"。
            // 纯主机名形状（字母数字- 加点分段）一律不当提及。
            if (isDomainShaped(name)) continue
            result += name.lowercase()
        }
        // 单条消息总量上限：per-candidate 的 guard 只防"一个名字重复一万次"的病态输入，
        // 不防"粘贴一段含成百上千个 @token 的日志"——那种输入会让 result 无界增长，
        // 并全额渲染进系统提示的未匹配段（fitSystemPrompt 只能从尾部整段砍）。
        val capped = if (result.size > MAX_MENTIONS_PER_MESSAGE) {
            result.take(MAX_MENTIONS_PER_MESSAGE)
        } else {
            result
        }
        return capped.toCollection(linkedSetOf())
    }

    /** 与第一轮正则 lookbehind `[\w.+-]` 同口径的 ASCII 词字符判定。 */
    private fun isAsciiWordChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '.' || c == '+' || c == '-'

    /**
     * 是否「域名形状」：如 `host.com`、`sub.example.co`。
     *
     * 这类 token 出现在 @ 后几乎总是邮箱/URL 的残余，不是用户想提及的能力名。
     * 已知名不受影响——它们走第一轮，没有这道过滤。
     */
    private fun isDomainShaped(name: String): Boolean {
        if (!name.contains('.')) return false
        val labels = name.split('.')
        if (labels.size < 2) return false
        return labels.all { label ->
            label.isNotEmpty() && label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }

    /** NFKC 归一：全角字符折半角，兼容全角 ＠ 与全角字母数字。 */
    private fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)

    /**
     * 单条消息的最大提及数。
     *
     * 两层用途：第一轮的 per-candidate guard 防"同一个名字重复一万次"的 O(n·m) 退化；
     * 收尾处的总量截断防"粘贴含成百上千个 @token 的日志"把结果集与系统提示顶爆。
     */
    private const val MAX_MENTIONS_PER_MESSAGE = 64
}
