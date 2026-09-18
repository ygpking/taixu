package top.wkbin.taixu.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.ContextBudgetDefaults
import top.wkbin.taixu.core.tools.AiProfileWriter

/**
 * 上下文预算「单一真相源」回归测试。
 *
 * 背景：这组数字（默认值 / 上下界）此前被镜像在四处，靠注释互相提醒「必须同值」：
 *   - core:datastore  SettingsDataStore（读写全局兜底预算）
 *   - harness         ContextWindowPolicy（引擎侧解析与钳制）
 *   - tools           AiProfileWriter（模型档案写入侧规范化）
 *   - feature 各模块   设置页 / 面板展示初值
 *
 * 一旦其中任一处漂移，用户就会看到「填多少 / 存多少 / 显示多少 / 按多少折叠」四处不一致
 * （例如填 200000 被存成 128000，或面板显示 500K 而引擎按 96K 折叠）。
 * 现全部收敛到 [ContextBudgetDefaults]，本测试用**编译期常量相等**把它们钉死：
 * 只要有人把任一处改回字面量或改成不同数值，这里立刻失败。
 *
 * 断言全部基于 `const val`，在编译期即可折叠，不依赖运行期反射，因此不会因混淆/裁剪失效。
 */
class ContextBudgetDefaultsTest {

    // ---- 层次 1：引擎侧（harness） ----

    @Test
    fun `engine budget constants delegate to single source of truth`() {
        assertEquals(ContextBudgetDefaults.DEFAULT_TOKENS, ContextWindowPolicy.DEFAULT_CONTEXT_BUDGET)
        assertEquals(ContextBudgetDefaults.MIN_TOKENS, ContextWindowPolicy.MIN_CONTEXT_BUDGET)
        assertEquals(ContextBudgetDefaults.MAX_TOKENS, ContextWindowPolicy.MAX_CONTEXT_BUDGET)
    }

    // ---- 层次 2：写入侧（tools / 模型档案） ----

    @Test
    fun `profile writer token bounds delegate to single source of truth`() {
        assertEquals(ContextBudgetDefaults.MIN_TOKENS, AiProfileWriter.MIN_CONTEXT_TOKENS)
        assertEquals(ContextBudgetDefaults.MAX_TOKENS, AiProfileWriter.MAX_CONTEXT_TOKENS)
    }

    @Test
    fun `profile writer normalize matches engine clamping`() {
        // 写入侧与引擎侧必须同语义：同样的输入 → 同样的输出
        val cases = listOf(null, -1, 0, 1, 3_999, 4_000, 128_000, 2_000_000, 2_000_001, Int.MAX_VALUE)
        cases.forEach { input ->
            val expected = input?.coerceIn(
                ContextWindowPolicy.MIN_CONTEXT_BUDGET,
                ContextWindowPolicy.MAX_CONTEXT_BUDGET,
            )
            assertEquals(
                "normalizeContextTokens($input) 必须与引擎钳制同结果",
                expected,
                AiProfileWriter.normalizeContextTokens(input),
            )
        }
    }

    // ---- 层次 3：数值本身的语义护栏 ----

    @Test
    fun `bounds are coherent and sane`() {
        assertTrue("下界必须为正", ContextBudgetDefaults.MIN_TOKENS > 0)
        assertTrue(
            "下界必须 <= 默认值 <= 上界",
            ContextBudgetDefaults.MIN_TOKENS <= ContextBudgetDefaults.DEFAULT_TOKENS &&
                ContextBudgetDefaults.DEFAULT_TOKENS <= ContextBudgetDefaults.MAX_TOKENS,
        )
        // 默认值必须在可接受区间内，否则「未配置」的兜底会被自己的钳制改掉
        assertEquals(
            ContextBudgetDefaults.DEFAULT_TOKENS,
            ContextBudgetDefaults.normalize(ContextBudgetDefaults.DEFAULT_TOKENS),
        )
    }

    @Test
    fun `normalize clamps out of range input`() {
        assertEquals(ContextBudgetDefaults.MIN_TOKENS, ContextBudgetDefaults.normalize(Int.MIN_VALUE))
        assertEquals(ContextBudgetDefaults.MIN_TOKENS, ContextBudgetDefaults.normalize(ContextBudgetDefaults.MIN_TOKENS - 1))
        assertEquals(ContextBudgetDefaults.MIN_TOKENS, ContextBudgetDefaults.normalize(ContextBudgetDefaults.MIN_TOKENS))
        assertEquals(ContextBudgetDefaults.MAX_TOKENS, ContextBudgetDefaults.normalize(ContextBudgetDefaults.MAX_TOKENS))
        assertEquals(ContextBudgetDefaults.MAX_TOKENS, ContextBudgetDefaults.normalize(ContextBudgetDefaults.MAX_TOKENS + 1))
        assertEquals(ContextBudgetDefaults.MAX_TOKENS, ContextBudgetDefaults.normalize(Int.MAX_VALUE))
    }
}
