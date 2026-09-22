package top.wkbin.taixu.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `MIGRATION_49_50`（技能名唯一约束）——本单唯一不可逆项，必须先在含重复行的表上验证。
 *
 * 关键性质：**先消歧、再建唯一索引**。直接 `CREATE UNIQUE INDEX` 在含重复行的表上会失败，
 * 而失败会被 `AppModule` 的 `fallbackToDestructiveMigration(dropAllTables = true)` 接住
 * → 整库销毁。消歧用「追加 id 末 4 位后缀」而非删行：本表无 updatedAt 之类可判断
 * 该留哪一行的标尺，删错即不可恢复的数据丢失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillNameUniqueIndexMigrationTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun writable() = database.openHelper.writableDatabase

    /** 造出 v49 形态：去掉唯一索引，插入重复行。 */
    private fun seedDuplicates() {
        val db = writable()
        db.execSQL("DROP INDEX IF EXISTS `index_agent_skills_name`")
        db.execSQL("DELETE FROM `agent_skills`")
        db.execSQL(
            """
            INSERT INTO `agent_skills`
                (`id`, `name`, `description`, `systemPrompt`, `triggerCommand`, `iconName`,
                 `isEnabled`, `isBuiltin`, `isImmutable`, `category`, `resourcePath`, `autoMatchEligible`)
            VALUES
                ('custom_a1', '周报整理', 'd', 'p', NULL, 'Code', 1, 0, 0, '进化', NULL, 1),
                ('custom_b2', '周报整理', 'd2', 'p2', NULL, 'Code', 1, 0, 0, '进化', NULL, 1),
                ('custom_c3', '周报整理', 'd3', 'p3', NULL, 'Code', 1, 0, 0, '进化', NULL, 1),
                ('solo_d4', '独一份', 'd', 'p', NULL, 'Code', 1, 0, 0, '进化', NULL, 1)
            """.trimIndent(),
        )
    }

    private fun ids(): List<String> =
        writable().query("SELECT `id` FROM `agent_skills` ORDER BY `id`").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun nameOf(id: String): String? =
        writable().query("SELECT `name` FROM `agent_skills` WHERE `id` = ?", arrayOf(id)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    @Test
    fun `duplicates are disambiguated without losing any row`() {
        seedDuplicates()
        assertEquals("前置：4 行（三行同名）", 4, ids().size)

        MIGRATION_49_50.migrate(writable())

        // 非破坏性：一行都不能少
        val remaining = ids()
        assertEquals("重名消歧不得删行", 4, remaining.size)
        // id 最小的一行保留原名，其余追加 id 末 4 位后缀
        assertEquals("id 最小的一行保留原名", "周报整理", nameOf("custom_a1"))
        assertEquals("周报整理-mb2", nameOf("custom_b2"))
        assertEquals("周报整理-mc3", nameOf("custom_c3"))
        // 不重名的行保持原样
        assertEquals("独一份", nameOf("solo_d4"))
        // 全部改名后名字互不相同
        val names = remaining.mapNotNull { nameOf(it) }
        assertEquals("改名后不得再有重名", names.size, names.toSet().size)
    }

    @Test
    fun `unique index is created and rejects duplicates afterwards`() {
        seedDuplicates()
        MIGRATION_49_50.migrate(writable())

        val db = writable()
        val ex = assertThrows("建索引后不得再插入同名技能", Exception::class.java) {
            db.execSQL(
                """
                INSERT INTO `agent_skills`
                    (`id`, `name`, `description`, `systemPrompt`, `triggerCommand`, `iconName`,
                     `isEnabled`, `isBuiltin`, `isImmutable`, `category`, `resourcePath`, `autoMatchEligible`)
                VALUES ('custom_dup', '周报整理', 'd', 'p', NULL, 'Code', 1, 0, 0, '进化', NULL, 1)
                """.trimIndent(),
            )
        }
        assertTrue(
            "应为唯一约束冲突，实际：${ex.message}",
            ex.message?.contains("unique", ignoreCase = true) == true ||
                ex.message?.contains(" constraint", ignoreCase = true) == true,
        )
    }

    @Test
    fun `migration is idempotent on an already unique table`() {
        seedDuplicates()
        MIGRATION_49_50.migrate(writable())
        val afterFirst = ids()
        // 再来一次不应报错、不应再删行（索引已存在用 IF NOT EXISTS，去重无重复可行）
        MIGRATION_49_50.migrate(writable())
        assertEquals(afterFirst, ids())
    }
}

/** MIGRATION_50_51：autoMatchEligible 加列带默认值，既有行全部回填为"允许"。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillAutoMatchEligibilityMigrationTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** 把 agent_skills 打回 v50 形态（没有 autoMatchEligible 列），才能真的跑到迁移分支。 */
    private fun downgradeToV50Shape() {
        val db = database.openHelper.writableDatabase
        db.execSQL("DROP TABLE IF EXISTS `agent_skills`")
        db.execSQL(
            """
            CREATE TABLE `agent_skills`
                (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL,
                 `systemPrompt` TEXT NOT NULL, `triggerCommand` TEXT, `iconName` TEXT NOT NULL,
                 `isEnabled` INTEGER NOT NULL, `isBuiltin` INTEGER NOT NULL,
                 `isImmutable` INTEGER NOT NULL, `category` TEXT NOT NULL,
                 `resourcePath` TEXT, PRIMARY KEY(`id`))
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `agent_skills`
                (`id`, `name`, `description`, `systemPrompt`, `triggerCommand`, `iconName`,
                 `isEnabled`, `isBuiltin`, `isImmutable`, `category`, `resourcePath`)
            VALUES ('c1', '技能一', 'd', 'p', NULL, 'Code', 1, 0, 0, '自定义', NULL),
                   ('c2', '技能二', 'd', 'p', NULL, 'Code', 1, 0, 0, '自定义', NULL)
            """.trimIndent(),
        )
    }

    @Test
    fun `add column backfills every existing row as eligible`() {
        downgradeToV50Shape()
        val db = database.openHelper.writableDatabase

        MIGRATION_50_51.migrate(db)

        val flags = db.query("SELECT `autoMatchEligible` FROM `agent_skills` ORDER BY `id`").use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0)) }
        }
        assertEquals("加列必须带默认值，既有行回填为 1（允许自动匹配）", listOf(1, 1), flags)
    }

    @Test
    fun `migration is idempotent`() {
        downgradeToV50Shape()
        val db = database.openHelper.writableDatabase
        MIGRATION_50_51.migrate(db)
        MIGRATION_50_51.migrate(db) // 重放不得抛 duplicate column
        val count = db.query("SELECT COUNT(*) FROM `agent_skills`").use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        assertEquals("重放不得改动数据", 2, count)
    }
}
