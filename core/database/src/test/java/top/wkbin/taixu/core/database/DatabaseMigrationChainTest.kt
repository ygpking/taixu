package top.wkbin.taixu.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 迁移链完整性回归。
 *
 * 背景（2026-09 上游 v0.17.0 审查发现）：[MIGRATION_29_30] 与 [MIGRATION_32_33]
 * 两个迁移对象从未在 [top.wkbin.taixu.di.AppModule.provideDatabase] 的
 * `addMigrations(...)` 列表中注册。后果是停留在 v29 / v32 schema 的存量设备
 * 升级时 Room 找不到迁移路径，而工程未配置 `fallbackToDestructiveMigration`
 * 之外的容忍策略，表现为数据库无法打开、应用启动崩溃。
 *
 * 这两个迁移最初由 fork 侧修复（commit ddd48ca6），本测试确保它们不会再次丢失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationChainTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun dbPath(name: String) = InstrumentationRegistry.getInstrumentation()
        .targetContext.getDatabasePath(name).absolutePath

    /**
     * 29→30 是本工程最大的一次结构变更：单表 harness_messages 拆成树形的
     * lanes / entries / operations / queue_items / usage / lane_results 六张表。
     * 缺失该迁移会让 v29 设备整库销毁，因此单独验证建表与索引是否齐全。
     */
    @Test
    fun `migration 29 to 30 creates harness tree tables`() {
        val path = dbPath("migration-29-30-chain")
        helper.createDatabase(path, 29).close()

        helper.runMigrationsAndValidate(path, 30, true, MIGRATION_29_30).use { db ->
            val tables = buildSet {
                db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                    while (cursor.moveToNext()) {
                        add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
            }
            listOf(
                "harness_entries",
                "harness_lanes",
                "harness_operations",
                "harness_queue_items",
                "harness_usage",
                "harness_lane_results",
            ).forEach { table ->
                assertTrue("29→30 应建出 $table，实际表集合=$tables", table in tables)
            }
            // 旧单表在迁移中显式丢弃（其数据在 v29 上已不可被当前代码读取）
            assertTrue("旧 harness_messages 应被移除", "harness_messages" !in tables)
        }
    }

    /** 32→33 只有 agent_skills 加一列；缺失同样会让 v32 设备整库销毁。 */
    @Test
    fun `migration 32 to 33 adds agent skills resource path column`() {
        val path = dbPath("migration-32-33-chain")
        helper.createDatabase(path, 32).close()

        helper.runMigrationsAndValidate(path, 33, true, MIGRATION_32_33).use { db ->
            val columns = buildSet {
                db.query("PRAGMA table_info(`agent_skills`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
            }
            assertTrue(
                "32→33 应新增 resourcePath 列，实际列集合=$columns",
                "resourcePath" in columns,
            )
        }
    }

    /** 版本号连续性：注册的迁移必须覆盖 [首个已定义版本]..@Database.version 的每一段。 */
    @Test
    fun `registered migrations cover every version step`() {
        org.junit.Assume.assumeTrue(
            "源码不可定位时跳过（不构成缺陷）",
            MigrationRegistry.isSourceAvailable(),
        )
        val pairs = MigrationRegistry.declaredMigrations()
            .mapNotNull { name ->
                val match = Regex("MIGRATION_(\\d+)_(\\d+)").find(name) ?: return@mapNotNull null
                match.groupValues[1].toInt() to match.groupValues[2].toInt()
            }
            .sortedBy { it.first }

        assertTrue("应解析出迁移版本对", pairs.isNotEmpty())

        // 只校验已有定义覆盖的区间：本工程历史迁移从 v27 起才有显式对象，
        // 更早版本由 Room 的自动迁移/初始建库负责，不属于本链的职责。
        val target = MigrationRegistry.declaredDatabaseVersion()
        assertTrue("@Database 版本号应可解析，实际=$target", target != null && target!! > 0)

        val byFrom = pairs.toMap()
        var cursor = pairs.first().first
        while (cursor < target!!) {
            val next = byFrom[cursor]
            assertEquals(
                "迁移链在 v$cursor 处断开：缺少 MIGRATION_${cursor}_${cursor + 1}",
                cursor + 1,
                next,
            )
            cursor += 1
        }
        assertEquals("链尾应抵达 @Database 声明的版本", target, cursor)
    }

    /**
     * 护栏测试：把 DatabaseMigrations.kt 实际声明的迁移与 AppModule 注册的列表对齐，
     * 任何"定义了迁移对象但忘记注册"的情形都会在此失败。
     */
    @Test
    fun `every declared migration is registered in database builder`() {
        org.junit.Assume.assumeTrue(
            "源码不可定位时跳过（不构成缺陷）",
            MigrationRegistry.isSourceAvailable(),
        )
        val declared = MigrationRegistry.declaredMigrations()
        assertTrue("应能在 DatabaseMigrations.kt 中发现迁移定义", declared.isNotEmpty())

        val registered = MigrationRegistry.registeredMigrationNames()
        val missing = declared.filter { it !in registered }.sorted()

        assertEquals(
            "存在已定义但未注册的迁移（会导致存量库升级失败）：$missing",
            emptyList<String>(),
            missing,
        )
    }
}
