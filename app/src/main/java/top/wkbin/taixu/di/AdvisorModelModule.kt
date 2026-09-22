package top.wkbin.taixu.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.harness.skill.AdvisorModelClient

/**
 * 把 `ProviderClient` 绑定到 [AdvisorModelClient] 窄接口。
 *
 * 不抽这个绑定时，`SkillEvolutionAdvisor` 只能依赖 `ProviderClient` 具体类，
 * 测试无法 fake——顾问的触发点/冷却/生命周期缺陷因此零覆盖。
 * `ProviderClient` 自身是 `@Singleton`，绑定无需再加作用域。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AdvisorModelModule {

    @Binds
    abstract fun bindAdvisorModelClient(impl: ProviderClient): AdvisorModelClient
}
