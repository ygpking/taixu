package top.wkbin.taixu.harness.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.core.model.BuiltinSkills

/**
 * 匹配精度基线：对**真实** `BuiltinSkills.presets` 语料跑日常任务样本。
 *
 * 为什么需要它：自动匹配把技能正文以「系统已验证」标签直接注入系统提示，误报的代价不是
 * 多几行文本，而是模型被明示"直接按它执行"。此前这条链路的精度主张**零测试背书**——
 * `SkillMatcherTest` 只用手搓的两个技能，恰好覆盖不到真实语料上的误报形态。
 *
 * 本文件既是精度基线（回归时先跑它），也是缺陷的固化反例：下面每个"不得命中"的任务
 * 都曾在旧口径下真实误报过。
 */
class SkillMatcherPrecisionBaselineTest {

    private val presets: List<AgentSkill> = BuiltinSkills.presets

    private fun hits(task: String) = SkillMatcher.match(task, presets)

    private fun assertNoHit(task: String) {
        val result = hits(task)
        assertTrue("日常任务不应自动命中任何技能：$task → ${result.map { it.skill.id }}", result.isEmpty())
    }

    // ---------- 误报反例（旧口径下全部真实发生过） ----------

    @Test
    fun `two unrelated generic bigrams do not convict`() {
        // 旧口径：两个不相干中文 2-gram 共现即 4 分过线（git_workflow / agent_context 曾中招）
        assertNoHit("这两个诊断结论有冲突，帮我复核一遍")
        assertNoHit("帮我看看分支机构的合作工作")
        assertNoHit("今天的工作任务比较多，帮我看看怎么安排")
        assertNoHit("对比一下这两个方案的工具链差异")
    }

    @Test
    fun `one english token plus one bigram does not convict`() {
        // 旧口径：一个英文词 + 一个中文 bigram = 5 分（android_reverse 曾中招：java + 开发）
        assertNoHit("用 java 开发一个页面")
        assertNoHit("帮我查一下 google 的文档怎么写")
        assertNoHit("总结这次 code review 的结论")
    }

    @Test
    fun `category words alone do not convict`() {
        // 旧口径：category 进语料，同分类所有技能共享类别词（code_refactor 曾中招）
        assertNoHit("项目的编程开发流程要怎么规划")
        assertNoHit("控制一下版本号的命名规范")
    }

    @Test
    fun `kdoc self-refuting example stays a miss`() {
        // SkillMatcher KDoc 自称"帮我构建 APK 不会命中"，旧口径下 flutter_dev 实际命中过
        assertNoHit("帮我构建 APK")
        assertNoHit("把这个原生 Android 项目打个包")
    }

    @Test
    fun `placeholder described skills are not matchable by boilerplate`() {
        // 目录扫描且无 frontmatter description 的技能：占位描述 + 固定 category「自定义」
        // 产出「目录/发现/自定/定义」四个高频 bigram，旧口径下任务共现两个即注入。
        val placeholder = AgentSkill(
            id = "custom_ph",
            name = "my-tool",
            description = top.wkbin.taixu.core.database.PLACEHOLDER_SKILL_DESCRIPTION,
            systemPrompt = "x",
            category = "自定义",
            isBuiltin = false,
        )
        assertTrue(
            "占位描述 + 固定 category 不得构成命中依据",
            SkillMatcher.match("扫描项目目录，发现构建脚本有问题", listOf(placeholder)).isEmpty(),
        )
        assertTrue(
            "『帮我看看目录里发现了什么配置文件』也不得命中",
            SkillMatcher.match("帮我看看目录里发现了什么配置文件", listOf(placeholder)).isEmpty(),
        )
    }

    @Test
    fun `short skill names cannot convict by substring`() {
        // 旧口径：全名命中是裸 contains，1–2 字符名任意子串即 6 分单独过线
        val short = AgentSkill(id = "s_go", name = "Go", description = "Golang 工具链与模块管理", systemPrompt = "x")
        assertTrue(SkillMatcher.match("帮我查一下 google 的文档怎么写", listOf(short)).isEmpty())
        assertTrue(SkillMatcher.match("看看 build 产物是否正常", listOf(short.copy(name = "ui"))).isEmpty())
        assertTrue(SkillMatcher.match("帮我把这个 class 重命名一下", listOf(short.copy(name = "C"))).isEmpty())
        assertTrue(SkillMatcher.match("连接云服务器上的数据库配置", listOf(short.copy(name = "库"))).isEmpty())
    }

    @Test
    fun `full width input no longer silently misses`() {
        // 旧口径：SkillMatcher 不做 NFKC，@提及链路做 → 同一输入两条路径结论相反
        val git = presets.first { it.id == "git_workflow" }
        assertTrue(
            "全角英文名应能命中（与 @提及链路同口径）",
            SkillMatcher.match("Ｇit 分支合并冲突诊断", listOf(git)).isNotEmpty(),
        )
    }

    // ---------- 召回基线（精度上调后，明确该命中的仍必须命中） ----------

    @Test
    fun `clear domain tasks still hit`() {
        val git = hits("帮我处理 git 分支合并冲突，生成提交信息")
        assertEquals("git_workflow", git.single().skill.id)

        val ops = hits("PRoot 沙箱里 dpkg 安装报 unable to securely remove .dpkg-tmp")
        assertEquals("linux_ops", ops.single().skill.id)

        val flutter = hits("flutter 项目用国内镜像构建并部署到手机")
        assertEquals("flutter_dev", flutter.single().skill.id)
    }

    @Test
    fun `full name mention still hits`() {
        val byName = hits("按 Git 敏捷工作流 处理这次改动")
        assertEquals("git_workflow", byName.single().skill.id)
    }
}
