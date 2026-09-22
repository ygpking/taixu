package top.wkbin.taixu.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.datastore.BudgetPreferences

/**
 * 把 `BudgetPreferences` 窄读接口绑定到实现类 [AgentPreferences]。
 *
 * **为什么必须有这个模块**：`BudgetPreferences` 是为「测试能廉价 fake」而抽出的窄接口
 * （见其 KDoc：`AgentPreferences` 是非 open 具体类、60+ 成员，无法廉价 fake，
 * 于是"某个消费方到底有没有读某项设置"这类缺陷写不出回归测试）。
 * 但只有实现侧的 `class AgentPreferences : BudgetPreferences`，**没有注入侧的绑定**，
 * 于是 `ContextBudgetResolver`（构造参数就是 `BudgetPreferences`）在 Hilt 里无人提供：
 *
 *     error: [Dagger/MissingBinding] top.wkbin.taixu.core.datastore.BudgetPreferences
 *     cannot be provided without an @Provides-annotated method.
 *
 * 该错误出在 `:app:hiltJavaCompileDebug`，会让整个 app 模块编译失败。
 *
 * 绑定方式与同目录 [AdvisorModelModule] 一致：`@Binds` + `SingletonComponent`。
 * `AgentPreferences` 自身是 `@Singleton`，绑定无需再加作用域。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BudgetPreferencesModule {

    @Binds
    abstract fun bindBudgetPreferences(impl: AgentPreferences): BudgetPreferences
}
