package top.wkbin.taixu.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * **真实升级路径**实测：v49 老库 → 当前版本，走 `Room` 的打开流程（迁移 + 结构校验），
 * 而不是手工调 `MIGRATION_x_y.migrate()`。
 *
 * 为什么必须这样测：既有的 `SkillNameUniqueIndexMigrationTest` 用
 * `Room.inMemoryDatabaseBuilder` 建出**当前版本**的库，再手工调用单条 `migrate()`。
 * 那条路径同时绕开了两件事：
 *  1. `onUpgrade` 的**迁移选取**（没装进 `addMigrations` 的迁移，在那条路径里照样"能跑"）；
 *  2. `RoomOpenHelper` 在迁移之后对**迁移产物**做的结构校验（`onValidateSchema`）。
 * 而 `index_agent_skills_name` 恰恰漏在"结构校验"这一端 —— 迁移里建了索引，
 * 实体 `@Entity` 却没声明 `indices`。于是老库（迁移后）有索引、新装库（`onCreate` 建表）没有索引，
 * 同一版本号对应两种结构。本测试就是用来钉死这条通路的。
 *
 * **关于修复前的实际后果，本条不预设结论**：Room 2.8.4 在"迁移跑完但结构校验不通过"时的
 * 具体处置（硬抛 `IllegalStateException` 还是交给 destructive fallback 清库），
 * 依赖其运行时实现，本机无法静态判定（本地无 Room 运行时产物，仅有 android-34，
 * 而项目 `compileSdk = 37`）。因此下面把两条路径**各测一遍**：
 *  - `fallback disabled` → 校验不通过则是硬失败（抛异常）；
 *  - `fallback enabled`（`AppModule` 里的真实配置）→ 校验不通过则可能静默清库。
 * 哪条先红，就说明是哪种后果。交付时以 CI 实测结果为准，不靠推断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseUpgradeFromV49Test {

    private companion object {
        /** 老库（v49 形态）文件名。 */
        const val LEGACY_DB = "taixu-upgrade-from-v49.db"

        /** 全新安装的库文件名，用于与升级结果做 A/B 结构对比。 */
        const val FRESH_DB = "taixu-fresh-v51.db"

        /**
         * v49 的 identityHash，取自 `core/database/schemas/.../49.json`。
         * 真机老库由 Room 创建，`room_master_table` 里存的就是它。
         * 值本身取自仓库快照，非本机重算（Room 的 identityHash 算法未复现）。
         */
        const val V49_IDENTITY_HASH = "934c718aff58c8c860c2491a864d099a"

        /** 当前 schema 版本（`AppDatabase.version`）。 */
        const val CURRENT_VERSION = 51

        /** PR#27 建的技能名唯一索引。 */
        const val SKILL_NAME_INDEX = "index_agent_skills_name"

        /** 写入一行技能用的列清单 + 占位符（**v49 形态**：无 `autoMatchEligible` 列）。 */
        const val INSERT_V49_SKILL_SQL =
            "INSERT INTO `agent_skills` (`id`,`name`,`description`,`systemPrompt`,`triggerCommand`," +
                "`iconName`,`isEnabled`,`isBuiltin`,`isImmutable`,`category`,`resourcePath`) " +
                "VALUES (?,?,'d','p',NULL,'Code',1,0,0,'进化',NULL)"

        /** 写入一行技能（**v51 形态**：含 `autoMatchEligible` 列）。用于升级后/全新库。 */
        const val INSERT_SKILL_SQL =
            "INSERT INTO `agent_skills` (`id`,`name`,`description`,`systemPrompt`,`triggerCommand`," +
                "`iconName`,`isEnabled`,`isBuiltin`,`isImmutable`,`category`,`resourcePath`," +
                "`autoMatchEligible`) VALUES (?,?,'d','p',NULL,'Code',1,0,0,'进化',NULL,1)"
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<AppDatabase>()

    @Before
    fun setUp() {
        removeDbFiles(LEGACY_DB)
        removeDbFiles(FRESH_DB)
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        removeDbFiles(LEGACY_DB)
        removeDbFiles(FRESH_DB)
    }

    private fun removeDbFiles(name: String) {
        val base = context.getDatabasePath(name).absolutePath
        listOf("", "-wal", "-shm", "-journal").forEach { File("$base$it").delete() }
    }

    // ------------------------------------------------------------------ 夹具

    /** v49 的完整建库脚本，由 `49.json` 原样生成（含该版本全部 33 张表与索引）。 */
    private fun v49Script(): String =
        checkNotNull(javaClass.getResourceAsStream("/schema_v49.sql")) {
            "缺少测试资源 schema_v49.sql —— 它是 v49 形态老库的唯一构造依据"
        }.bufferedReader().use { it.readText() }

    private fun execScript(db: SQLiteDatabase, script: String) {
        script.lineSequence()
            .map { it.substringBefore("--").trim() }
            .filter { it.isNotEmpty() }
            .forEach { db.execSQL(it) }
    }

    /**
     * 造出一个**真实的 v49 库**：完整 33 张表 + `room_master_table`（含 v49 identityHash）
     * + 3 行哨兵数据 + `user_version = 49`。
     *
     * 哨兵选列名确定的小表，升级后逐行核对，任何一行消失都说明数据丢了。
     */
    private fun seedLegacyV49() {
        val file = context.getDatabasePath(LEGACY_DB)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            execScript(db, v49Script())
            // 真机上的老库由 Room 创建，必然带 room_master_table
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY,identity_hash TEXT)",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42,'$V49_IDENTITY_HASH')",
            )
            db.execSQL(
                "INSERT INTO `workspaces` (`name`,`path`,`createdAt`,`ownsDirectory`) " +
                    "VALUES ('ws-sentinel','/tmp/ws-sentinel',111,0)",
            )
            db.execSQL("INSERT INTO `agent_approval_settings` (`id`,`mode`) VALUES (1,'AUTO')")
            // 注意：此处必须用 **v49 形态**的 INSERT —— 此刻还没有 autoMatchEligible 列
            db.execSQL(INSERT_V49_SKILL_SQL, arrayOf("k-sentinel", "周报整理"))
            db.version = 49
        } finally {
            db.close()
        }
    }

    /** 用 Room 真实打开（迁移与结构校验都发生在这一步）。 */
    private fun openRoom(
        name: String,
        migrations: Array<Migration>,
        dropAllTablesOnFailure: Boolean,
    ): AppDatabase {
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*migrations)
            .allowMainThreadQueries()
        if (dropAllTablesOnFailure) {
            builder.fallbackToDestructiveMigration(dropAllTables = true)
        }
        val db = builder.build()
        opened += db
        // 触碰一次数据库，强制完成 open → onUpgrade → onValidateSchema
        db.openHelper.writableDatabase
        return db
    }

    // ------------------------------------------------------------------ 通用读取

    /** 取查询结果的第 [index] 列。注意 `PRAGMA` 系列的第 0 列往往不是名字，调用点需显式指定。 */
    private fun column(db: AppDatabase, sql: String, index: Int = 0): List<String> =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(if (cursor.isNull(index)) "NULL" else cursor.getString(index))
                }
            }
        }

    private fun int(db: AppDatabase, sql: String): Int =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst(); cursor.getInt(0)
        }

    private fun userVersion(db: AppDatabase): Int = db.openHelper.writableDatabase.version

    /** 用 table-valued pragma（而非 `PRAGMA` 语句）以免触发 Room 对非查询语句的拦截。 */
    private fun columnsOf(db: AppDatabase): List<String> =
        column(db, "SELECT `name` FROM pragma_table_info('agent_skills')")

    private fun indexesOf(db: AppDatabase): List<String> = column(
        db,
        "SELECT `name` FROM sqlite_master WHERE type='index' AND tbl_name='agent_skills' " +
            "AND `name` NOT LIKE 'sqlite_%' ORDER BY `name`",
    )

    private fun createSqlOf(db: AppDatabase): String = column(
        db,
        "SELECT `sql` FROM sqlite_master WHERE type='table' AND tbl_name='agent_skills'",
    ).joinToString("")

    private fun tableCount(db: AppDatabase): Int = int(
        db,
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'",
    )

    // ------------------------------------------------------------------ 断言

    /** 对升级结果做完整核对：版本、表数、哨兵数据、PR#27/PR#28 的 schema 变更是否都到位。 */
    private fun assertUpgradeResultIntact(db: AppDatabase) {
        assertEquals("升级后必须停在当前 schema 版本", CURRENT_VERSION, userVersion(db))
        assertEquals("33 张表一张都不能少（少了就是被清库重建）", 33, tableCount(db))

        // 哨兵数据必须原样存活 —— 这是"有没有清库"的直接判据
        assertEquals(
            "workspaces 哨兵行丢了 → 数据被清空",
            listOf("/tmp/ws-sentinel"),
            column(db, "SELECT `path` FROM `workspaces` WHERE `name`='ws-sentinel'"),
        )
        assertEquals(
            "agent_approval_settings 哨兵行丢了",
            listOf("AUTO"),
            column(db, "SELECT `mode` FROM `agent_approval_settings` WHERE `id`=1"),
        )
        assertEquals(
            "agent_skills 哨兵行丢了",
            listOf("周报整理"),
            column(db, "SELECT `name` FROM `agent_skills` WHERE `id`='k-sentinel'"),
        )

        // 升级后半程真的发生了：PR#27 / PR#28 的两项 schema 变更必须都到位
        assertEquals(
            "迁移后 agent_skills 必须存在技能名唯一索引，实际：${indexesOf(db)}",
            listOf(SKILL_NAME_INDEX),
            indexesOf(db),
        )
        assertEquals(
            "autoMatchEligible 列必须存在且既有行回填为 1",
            listOf("1"),
            column(db, "SELECT `autoMatchEligible` FROM `agent_skills` WHERE `id`='k-sentinel'"),
        )
        assertEquals(
            "agent_skills 升级后的列顺序/列名必须与实体声明完全一致",
            listOf(
                "id", "name", "description", "systemPrompt", "triggerCommand", "iconName",
                "isEnabled", "isBuiltin", "isImmutable", "category", "resourcePath",
                "autoMatchEligible",
            ),
            columnsOf(db),
        )
    }

    /**
     * 唯一约束的**行为**验证：同名技能写第二行必须被数据库拒绝。
     *
     * 这比"检查 `sqlite_master` 里有没有那个索引"更贴近 PR#27 的真实承诺
     * （"重名写入被 DB 挡下"），也顺带证明索引真的是 `UNIQUE` 而不只是"存在"。
     */
    private fun assertDuplicateSkillNameRejected(db: AppDatabase) {
        db.openHelper.writableDatabase.execSQL(INSERT_SKILL_SQL, arrayOf("dup-1", "重名技能"))

        val thrown = runCatching {
            db.openHelper.writableDatabase.execSQL(INSERT_SKILL_SQL, arrayOf("dup-2", "重名技能"))
        }.exceptionOrNull()

        assertNotNull("同名技能写入第二行竟然成功了 —— 唯一约束没生效", thrown)
        assertTrue(
            "异常应为 UNIQUE 约束冲突，实际：${thrown!!.javaClass.name}: ${thrown.message}",
            thrown.message?.contains("UNIQUE", ignoreCase = true) == true,
        )
    }

    // ------------------------------------------------------------------ 1. 核心契约：两种路径结构必须一致

    /**
     * **本文件最重要的一条**：把「老库升级后的 agent_skills 结构」与「全新安装的 agent_skills 结构」
     * 摆在一起逐项对比。同一版本号必须只有一种结构。
     *
     * 修复前 `AgentSkillEntity` 没有 `indices` 声明：
     *  - 升级库：索引来自 `MIGRATION_49_50` 的 DDL → **有** `index_agent_skills_name`；
     *  - 新装库：结构来自实体声明 → **没有**（`onCreate` 按实体声明建表）。
     * 两边索引集合不等 → 本条会失败。
     *
     * 需要说明置信度："修复前本条会失败"是**基于静态对比的推断，未在 CI 上实测过**
     * （修复前的树不会去跑测试）。已坐实的静态证据是：全仓 25 个由迁移创建的索引中，
     * 24 个在对应实体上有 `indices` 声明，唯一例外正是 `agent_skills` 这一张。
     * 本条测试的价值在于**修复之后把正确形态钉死**：任何一边退回（迁移删索引、
     * 或实体丢声明），这一条都会立刻变红。
     */
    @Test
    fun `migrated and freshly created databases have identical agent_skills structure`() {
        seedLegacyV49()
        val migrated = openRoom(LEGACY_DB, ALL_MIGRATIONS, dropAllTablesOnFailure = false)
        val fresh = openRoom(FRESH_DB, ALL_MIGRATIONS, dropAllTablesOnFailure = false)

        assertEquals("列名与顺序必须一致", columnsOf(fresh), columnsOf(migrated))
        assertEquals("索引集合必须一致（升级路径 vs 新装路径）", indexesOf(fresh), indexesOf(migrated))

        // 防"两边都缺索引也算一致"：共同结构里必须真的带这个唯一索引
        assertEquals(
            "两条路径都必须带技能名唯一索引",
            listOf(SKILL_NAME_INDEX),
            indexesOf(migrated),
        )

        // 行为一致：两边都必须拒绝重名
        assertDuplicateSkillNameRejected(migrated)
        assertDuplicateSkillNameRejected(fresh)

        // 已知的**第二处分叉**（本次不修、不判失败，只输出供 CI 日志留痕）：
        // `MIGRATION_50_51` 用 `ADD COLUMN ... NOT NULL DEFAULT 1` 加列，SQLite 会把这段
        // DDL 原文写回 sqlite_master，升级库的建表文本因此多出 `DEFAULT 1`；
        // 而实体只声明了 `Boolean = true` 的 Kotlin 默认值、没有 `@ColumnInfo(defaultValue = "1")`，
        // 新装库的建表文本不带 DEFAULT。
        // 性质澄清：这是"两条路径的建表文本不同"，**不是**本次修复针对的结构分叉 ——
        // 列名、列顺序、索引集合全部一致，功能不受影响。是否统一属独立议题。
        println("[upgrade-parity] migrated createSql = ${createSqlOf(migrated)}")
        println("[upgrade-parity] fresh    createSql = ${createSqlOf(fresh)}")
    }

    // ------------------------------------------------------------------ 2. 硬校验：不带 fallback

    /**
     * 关掉 destructive fallback，把"迁移产物的结构校验是否通过"**单独隔离出来**。
     *
     * 带 fallback 时，"校验失败但数据恰好还在"与"校验通过"从断言上看不出区别；
     * 不带 fallback 时校验失败会直接抛异常，是硬失败。
     * 本条通过 = 迁移产出的结构被 Room 认可，老用户升级不会丢数据。
     */
    @Test
    fun `v49 upgrade passes Room structural validation with fallback disabled`() {
        seedLegacyV49()

        val db = openRoom(LEGACY_DB, ALL_MIGRATIONS, dropAllTablesOnFailure = false)

        assertUpgradeResultIntact(db)
        assertDuplicateSkillNameRejected(db)
    }

    // ------------------------------------------------------------------ 3. 用户实际路径：带 fallback

    /**
     * 用户实际会走的路径（`AppModule` 里就是 `dropAllTables = true`）。
     * 与上一条合起来才能完整回答"升级到底会怎样"。
     */
    @Test
    fun `v49 database upgrades to current version keeping all data`() {
        seedLegacyV49()

        val db = openRoom(LEGACY_DB, ALL_MIGRATIONS, dropAllTablesOnFailure = true)

        assertUpgradeResultIntact(db)
        assertDuplicateSkillNameRejected(db)
    }

    // ------------------------------------------------------------------ 4. 阳性对照

    /**
     * 阳性对照——证明本测试真的在观察升级通路，不是空转。
     *
     * 把 49→50、50→51 两条迁移从集合里拿掉，同一份 v49 老库在带 fallback 的配置下
     * 应被**整库清空**。若这个对照不成立，说明上面"数据存活"的断言没有鉴别力。
     */
    @Test
    fun `control - missing migrations really do wipe the database`() {
        seedLegacyV49()

        val withoutRecent = ALL_MIGRATIONS
            .filterNot { it.startVersion == 49 && it.endVersion == 50 }
            .filterNot { it.startVersion == 50 && it.endVersion == 51 }
            .toTypedArray()
        assertEquals("对照前置：应只移除 2 条迁移", ALL_MIGRATIONS.size - 2, withoutRecent.size)

        val db = openRoom(LEGACY_DB, withoutRecent, dropAllTablesOnFailure = true)

        assertEquals(
            "对照失效：缺迁移时哨兵行居然还在，说明本测试没有鉴别力",
            emptyList<String>(),
            column(db, "SELECT `path` FROM `workspaces` WHERE `name`='ws-sentinel'"),
        )
    }
}
