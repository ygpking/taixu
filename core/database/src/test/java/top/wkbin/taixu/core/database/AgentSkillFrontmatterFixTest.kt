package top.wkbin.taixu.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * frontmatter 解析的两个缺陷修复的回归测试（预期值已用逻辑复算逐条核对）。
 *
 * 缺陷 1（引号键丢失）：旧实现 `substringBefore(':')` 不区分引号内外，且**不剥 key 上的引号**。
 *   - `"name": pdf-export` → key 变成 `"name"`（含引号），于是 `meta["name"]` 取不到 ——
 *     技能名静默回退到目录名/首行标题；
 *   - `"a:b": v` → 在引号内的冒号处断开，得到 `{"a": "b\": v"}` 这样的垃圾条目。
 *   实测（逻辑复算）：旧实现 `{'\"name\"': 'pdf-export'}` / `{'\"a': 'b\": v'}`。
 *
 * 缺陷 2（`|` 与 `>` 不分）：块标量一律按折叠块处理（空格连接），
 *   `description: |` 的多行描述被压成一整行。实测旧实现 `'第一行 第二行'`，
 *   按 YAML 规范字面块应为 `'第一行\n第二行'`。
 */
class AgentSkillFrontmatterFixTest {

    private fun parse(md: String) = AgentSkillRepository.parseFrontmatter(md.trimIndent())

    @Test
    fun `quoted key is parsed whole not truncated at the inner colon`() {
        val meta = parse(
            """
            ---
            "name": pdf-export
            description: Export documents to PDF
            ---
            body
            """,
        )
        // 修复前：key 变成 `"na`，且 name 字段整体消失
        assertEquals("pdf-export", meta["name"])
        assertEquals("Export documents to PDF", meta["description"])
        assertFalse("不应留下被引号截断的垃圾键", meta.keys.any { it.startsWith("\"") })
    }

    @Test
    fun `single quoted key is also parsed whole`() {
        val meta = parse(
            """
            ---
            'name': notes
            ---
            body
            """,
        )
        assertEquals("notes", meta["name"])
    }

    @Test
    fun `value containing a colon keeps the rest intact`() {
        val meta = parse(
            """
            ---
            name: ns
            description: 见 https://example.com/a:b 的说明
            ---
            body
            """,
        )
        assertEquals("见 https://example.com/a:b 的说明", meta["description"])
    }

    @Test
    fun `literal block preserves newlines while folded block collapses them`() {
        val literal = parse(
            """
            ---
            name: x
            description: |
              第一行
              第二行
            ---
            body
            """,
        )
        // `|` 是字面块：换行保留
        assertEquals("第一行\n第二行", literal["description"])

        val folded = parse(
            """
            ---
            name: x
            description: >
              第一行
              第二行
            ---
            body
            """,
        )
        // `>` 是折叠块：换行折成空格
        assertEquals("第一行 第二行", folded["description"])
    }

    @Test
    fun `chomping modifiers keep the same block style`() {
        val literalStrip = parse(
            """
            ---
            name: x
            description: |-
              a
              b
            ---
            body
            """,
        )
        assertEquals("a\nb", literalStrip["description"])

        val foldedStrip = parse(
            """
            ---
            name: x
            description: >-
              a
              b
            ---
            body
            """,
        )
        assertEquals("a b", foldedStrip["description"])
    }

    @Test
    fun `existing behaviours are not regressed`() {
        // 无引号 + 行内注释剥离
        assertEquals("v1", parse("---\nname: v1 # 备选名\n---\nbody")["name"])
        // 双引号值里的 # 保留
        assertEquals("a #b", parse("---\nname: \"a #b\"\n---\nbody")["name"])
        // 无 frontmatter → 空
        assertTrue(parse("no frontmatter here").isEmpty())
        // 键大小写不敏感
        assertEquals("v", parse("---\nNAME: v\n---\nbody")["name"])
        // 空值不落库
        assertNull(parse("---\nname:\n---\nbody")["name"])
    }

    /** 单引号内的双引号不应翻转双引号状态（反之亦然）。 */
    @Test
    fun `quote state is tracked per quote kind`() {
        val meta = parse(
            """
            ---
            name: 'a "quoted" b'
            description: ok
            ---
            body
            """,
        )
        assertEquals("a \"quoted\" b", meta["name"])
        assertEquals("ok", meta["description"])
    }
}
