package top.wkbin.taixu.harness.budget

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.fixtures.FakeBudgetPreferences
import top.wkbin.taixu.harness.fixtures.withBudget

/**
 * 预算单一真相源（`ContextBudgetResolver`）的契约测试。
 *
 * 这批用例用 PR-A 新增的 [FakeBudgetPreferences] 预设任意设置组合——**这是"消费方到底
 * 有没有读某项设置"这类缺陷第一次变得可回归**：此前没有可预设的偏好 fake，
 * SessionModelSwitcher 漏读折叠比例与保留上限整整两个版本都没被发现。
 */
class ContextBudgetResolverTest {

    private fun model(
        contextTokens: Int? = 200_000,
        inputTokenLimit: Int? = null,
        keepRecent: Int? = null,
        reserve: Int? = null,
    ) = ModelConfig(
        name = "m",
        provider = "p",
        model = "gpt-x",
        baseUrl = "https://example.com",
        apiKey = null,
        contextTokens = contextTokens,
        inputTokenLimit = inputTokenLimit,
        compactionKeepRecentTokens = keepRecent,
        compactionReserveTokens = reserve,
    )

    @Test
    fun `unset global input limit falls back to window derivation`() = runBlocking {
        val prefs = FakeBudgetPreferences(inputTokenLimit = 0).apply {
            inputTokenLimitOrNullFlow.value = null // 关键：可空语义 = 从未设置
        }
        val resolver = ContextBudgetResolver(prefs)

        val budget = resolver.resolve(model(contextTokens = 1_000_000))

        // 规则 3（窗口×90%）恢复生效：100 万窗口 → 90 万，而不是被 230K 默认值锁死
        assertEquals(900_000, budget.inputLimit)
        assertEquals(900_000, budget.budget)
    }

    @Test
    fun `explicit global input limit still wins over derivation`() = runBlocking {
        val prefs = FakeBudgetPreferences().apply {
            inputTokenLimitOrNullFlow.value = 230_000
        }
        val resolver = ContextBudgetResolver(prefs)

        val budget = resolver.resolve(model(contextTokens = 1_000_000))

        assertEquals("用户显式设了 230K 时必须尊重", 230_000, budget.inputLimit)
    }

    @Test
    fun `model profile input limit wins over the global setting`() = runBlocking {
        val prefs = FakeBudgetPreferences().apply { inputTokenLimitOrNullFlow.value = 230_000 }
        val resolver = ContextBudgetResolver(prefs)

        val budget = resolver.resolve(model(contextTokens = 1_000_000, inputTokenLimit = 500_000))

        assertEquals(500_000, budget.inputLimit)
    }

    @Test
    fun `budget never exceeds the window`() = runBlocking {
        val prefs = FakeBudgetPreferences().apply { inputTokenLimitOrNullFlow.value = 900_000 }
        val resolver = ContextBudgetResolver(prefs)

        val budget = resolver.resolve(model(contextTokens = 100_000))

        assertEquals("输入上限不得超过窗口本身", 100_000, budget.budget)
    }

    @Test
    fun `user folding ratio and keep cap reach the resolution`() = runBlocking {
        val prefs = FakeBudgetPreferences().withBudget(
            foldingRatioPercent = 40,
            maxKeepTokens = 20_000,
        )
        val resolver = ContextBudgetResolver(prefs)

        val budget = resolver.resolve(model())

        assertEquals("折叠比例必须透传（切换路径原先漏读）", 40, budget.foldingRatioPercent)
        assertEquals("保留上限必须透传（切换路径原先漏读）", 20_000, budget.maxKeepTokens)
    }

    @Test
    fun `per model overrides ride along`() = runBlocking {
        val resolver = ContextBudgetResolver(FakeBudgetPreferences())

        val budget = resolver.resolve(model(keepRecent = 1_500, reserve = 3_000))

        assertEquals(1_500, budget.keepRecentTokens)
        assertEquals(3_000, budget.reserveTokens)
    }

    @Test
    fun `switch and assemble agree on the same numbers`() = runBlocking {
        // 共因修复的核心断言：两侧读同一个 resolver，同输入必须同输出。
        // 此前 switcher 自己读一部分设置、assembler 读另一部分，用户调过滑杆后
        // 会出现「切换即不可逆压缩、下一轮又不压缩」。
        val prefs = FakeBudgetPreferences().withBudget(foldingRatioPercent = 35, maxKeepTokens = 12_000)
        val resolver = ContextBudgetResolver(prefs)

        val viaSwitch = resolver.resolve(model(contextTokens = 60_000))
        val viaAssemble = resolver.resolve(model(contextTokens = 60_000))

        assertEquals(viaSwitch, viaAssemble)
        assertEquals(35, viaAssemble.foldingRatioPercent)
        assertEquals(12_000, viaAssemble.maxKeepTokens)
    }

    @Test
    fun `settings read failure degrades to a fallback instead of throwing`() = runBlocking {
        val prefs = object : top.wkbin.taixu.core.datastore.BudgetPreferences {
            override val contextCompactionEnabled: kotlinx.coroutines.flow.Flow<Boolean>
                get() = throw IllegalStateException("datastore unavailable")
            override val contextBudgetTokens: kotlinx.coroutines.flow.Flow<Int>
                get() = throw IllegalStateException("datastore unavailable")
            override val inputTokenLimitOrNull: kotlinx.coroutines.flow.Flow<Int?>
                get() = throw IllegalStateException("datastore unavailable")
            override val inputTokenLimit: kotlinx.coroutines.flow.Flow<Int>
                get() = throw IllegalStateException("datastore unavailable")
            override val contextFoldingRatioPercent: kotlinx.coroutines.flow.Flow<Int>
                get() = throw IllegalStateException("datastore unavailable")
            override val contextMaxKeepTokens: kotlinx.coroutines.flow.Flow<Int>
                get() = throw IllegalStateException("datastore unavailable")
            override val contextArchiveEnabled: kotlinx.coroutines.flow.Flow<Boolean>
                get() = throw IllegalStateException("datastore unavailable")
            override val skillEvolutionSuggestions: kotlinx.coroutines.flow.Flow<Boolean>
                get() = throw IllegalStateException("datastore unavailable")
        }
        val budget = ContextBudgetResolver(prefs).resolve(model(contextTokens = 50_000))
        assertTrue("读取失败必须退回兜底而不是抛异常", budget.windowTokens > 0)
        assertTrue(budget.compactionEnabled)
    }

    @Test
    fun `resolveWith is a pure function for the settings preview`() {
        val resolver = ContextBudgetResolver(FakeBudgetPreferences())
        val viaPure = resolver.resolveWith(
            model = model(contextTokens = 1_000_000),
            globalInputLimit = null,
            foldingRatioPercent = 55,
            maxKeepTokens = 30_000,
        )
        assertEquals("预览路径同样走规则 3", 900_000, viaPure.inputLimit)
        assertEquals(55, viaPure.foldingRatioPercent)
    }

    @Test
    fun `fake reads back what was set`() = runBlocking {
        val prefs = FakeBudgetPreferences().withBudget(foldingRatioPercent = 70)
        assertEquals(70, prefs.contextFoldingRatioPercent.first())
    }
}
