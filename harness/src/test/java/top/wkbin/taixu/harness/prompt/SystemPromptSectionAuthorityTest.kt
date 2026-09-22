package top.wkbin.taixu.harness.prompt

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示的**段落权威排序**（第二波回归，P1）。
 *
 * `fitSystemPrompt` 超预算时从头部保留、从尾部丢弃（head-cut），因此段落顺序就是裁剪优先级。
 * 旧顺序把 pinned（自述"最高权威"）、Active Plan、工具调用协议放在尾部，而逐轮变化的技能正文
 * 放在第 2 位——超预算时先丢的是用户长期指令与任务计划，留下的却可能是误报注入的技能正文。
 */
class SystemPromptSectionAuthorityTest {

    @Test
    fun `high authority sections precede the variable skill and recall tail`() {
        val source = readBuilderSource()
        val order = source.substringAfter("return listOf(")
            .substringBefore(").filter { it.isNotBlank() }")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        fun indexOf(name: String): Int = order.indexOfFirst { it == name }
        assertTrue("找不到 return listOf( 段", order.isNotEmpty())

        val pinned = indexOf("pinnedSection")
        val plan = indexOf("planSection")
        val toolCall = indexOf("toolCallSection")
        val skill = indexOf("skillSectionFallback")
        val recall = indexOf("recallSection")

        assertTrue("pinnedSection 必须在场", pinned >= 0)
        assertTrue("planSection 必须在场", plan >= 0)
        assertTrue("toolCallSection 必须在场", toolCall >= 0)
        assertTrue("skillSectionFallback 必须在场", skill >= 0)
        assertTrue("recallSection 必须在场", recall >= 0)

        assertTrue("pinned（最高权威）必须排在技能正文之前", pinned < skill)
        assertTrue("Active Plan 必须排在技能正文之前", plan < skill)
        assertTrue("工具调用协议必须排在技能正文之前", toolCall < skill)
        assertTrue("技能正文必须排在 recall 之前（逐轮变化的沉到最后）", skill < recall)
    }

    private fun readBuilderSource(): String {
        val path = "src/main/java/top/wkbin/taixu/harness/prompt/SystemPromptBuilder.kt"
        val file = java.io.File(path)
        assertTrue("找不到 $path", file.exists())
        return file.readText()
    }
}
