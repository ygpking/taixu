package top.wkbin.taixu.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionExtractorTest {

    @Test
    fun `parses lowercased mention names`() {
        val names = MentionExtractor.parse("@Builder 帮我看看 @CodeR 的实现")
        assertEquals(setOf("builder", "coder"), names)
    }

    @Test
    fun `stops at chinese punctuation and colon boundaries`() {
        val names = MentionExtractor.parse("联系 @Skill，或 @x:y 再问 @trigger_cmd")
        assertEquals(setOf("skill", "x", "trigger_cmd"), names)
    }

    @Test
    fun `email is not treated as mention`() {
        // 邮箱保护：@ 前是词字符时不视为提及（旧行为会把 host.com 当候选名，导致误报）。
        assertTrue(MentionExtractor.parse("邮箱 user@host.com").isEmpty())
    }

    @Test
    fun `text without at returns empty`() {
        assertTrue(MentionExtractor.parse("没有提及的普通消息").isEmpty())
    }

    // ---- 以下为修复「含空格技能名静默失效」新增的回归用例 ----

    @Test
    fun `known name with internal space matches as a whole`() {
        // 核心缺陷回归：旧实现把「Git 敏捷工作流」截成「Git」→ 技能静默不生效。
        val names = MentionExtractor.parse(
            "@Git 敏捷工作流 帮我回退这个合并",
            listOf("Git 敏捷工作流", "git_workflow"),
        )
        assertEquals(setOf("git 敏捷工作流"), names)
    }

    @Test
    fun `known name match is longest-first so short prefix does not win`() {
        // 「Git」与「Git 敏捷工作流-进化」同时存在时，必须命中更长的那个。
        val names = MentionExtractor.parse(
            "@Git 敏捷工作流-进化 沉淀规则",
            listOf("Git", "Git 敏捷工作流", "Git 敏捷工作流-进化"),
        )
        assertEquals(setOf("git 敏捷工作流-进化"), names)
    }

    @Test
    fun `fullwidth at sign and letters are normalized`() {
        // NFKC 归一：全角 ＠ 与全角字母都应命中。
        assertEquals(
            setOf("git 敏捷工作流"),
            MentionExtractor.parse("＠Git 敏捷工作流 修复", listOf("Git 敏捷工作流")),
        )
        assertEquals(
            setOf("git 敏捷工作流"),
            MentionExtractor.parse("@Ｇｉｔ 敏捷工作流 修复", listOf("Git 敏捷工作流")),
        )
    }

    @Test
    fun `chinese full stop terminates the mention`() {
        assertEquals(
            setOf("codegraph"),
            MentionExtractor.parse("@CodeGraph。帮我看看", listOf("CodeGraph")),
        )
    }

    @Test
    fun `brackets terminate the mention`() {
        assertEquals(
            setOf("android_reverse"),
            MentionExtractor.parse("看下 @android_reverse）然后继续", listOf("android_reverse")),
        )
    }

    @Test
    fun `multiple mentions in one message are all collected`() {
        val names = MentionExtractor.parse(
            "@code_graph @git_workflow 一起用",
            listOf("code_graph", "git_workflow"),
        )
        assertEquals(setOf("code_graph", "git_workflow"), names)
    }

    @Test
    fun `unknown entity still collected by generic fallback`() {
        assertEquals(
            setOf("some_unknown_service"),
            MentionExtractor.parse("@some_unknown_service 试试", listOf("已知技能")),
        )
    }

    @Test
    fun `known name does not swallow longer unrelated token`() {
        // 边界保护：候选名后必须紧跟终止符或文本结束，"Git" 不得命中 "GitHub" 的前缀。
        // 注意结果仍是 GitHub 本身（由通用兜底作为未登记实体提取），关键是**不能变成 "git"**。
        val names = MentionExtractor.parse("@GitHub 仓库", listOf("Git"))
        assertEquals(setOf("github"), names)
    }

    @Test
    fun `leading slash in known trigger command is tolerated`() {
        assertEquals(
            setOf("git-workflow"),
            MentionExtractor.parse("@git-workflow 提交", listOf("/git-workflow")),
        )
    }

    // ---- 以下为「两轮口径不一致 / 域名误判 / 总量无上限」的回归用例 ----

    @Test
    fun `unknown mention after a CJK character is still collected`() {
        // 回归点：通用轮原先按 Char.isLetterOrDigit()（Unicode）判定"@ 前是词字符"，
        // 而第一轮的正则 lookbehind 只认 ASCII \w —— 同一个「请用@未知技能」，
        // 第一轮能命中已知名、第二轮却因前一字是中文被静默丢弃，两轮结论相反。
        assertEquals(
            setOf("未知技能"),
            MentionExtractor.parse("请用@未知技能 处理", emptyList()),
        )
        // ASCII 词字符前的 @ 仍然按邮箱/标识符保护丢弃
        assertTrue(MentionExtractor.parse("用户abc@host").isEmpty())
    }

    @Test
    fun `domain shaped token after at is not a mention`() {
        // 「用户@host.com」：@ 前是中文（非 ASCII 词字符）放行，但 host.com 是域名形状，
        // 不该被当成"未登记实体"收进来再报"技能名拼错了"。
        assertTrue(MentionExtractor.parse("联系 用户@host.com 反馈").isEmpty())
        assertTrue(MentionExtractor.parse("参考@sub.example.co 的文档").isEmpty())
        // 普通未知名（不含点）不受影响
        assertEquals(setOf("some_service"), MentionExtractor.parse("@some_service 试试", emptyList()))
    }

    @Test
    fun `known name containing a dot still matches via round one`() {
        // 域名形状过滤只作用于通用轮；已登记名字（如 "node.js"）仍走第一轮，不受影响。
        assertEquals(
            setOf("node.js"),
            MentionExtractor.parse("@node.js 装依赖", listOf("node.js")),
        )
    }

    @Test
    fun `mention count is capped per message`() {
        // 粘贴含成百上千个 @token 的日志：结果集必须有上限，
        // 否则未匹配段会全额渲染进系统提示、把预算顶爆。
        val spam = (1..500).joinToString(" ") { "@tok$it" }
        val names = MentionExtractor.parse(spam, emptyList())
        assertTrue("提及数应被截断，实际 ${names.size}", names.size <= 64)
    }
}
