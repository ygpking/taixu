package top.wkbin.taixu.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.model.BuiltinSkills

/**
 * 内置技能播种的**内容同步**回归测试。
 *
 * 缺陷：`ensureInitialized` 用 `insertAll(IGNORE)`，只补缺、不更新。
 * 内置技能的正文在代码里修过之后，**已安装用户永远拿不到**（那行早就存在，
 * IGNORE 直接跳过）；手机端无法像开发机那样清库，于是"改了技能但用户看不到"
 * 会一直静默持续。同族对比：`AgentSubagentDao.syncBuiltinCatalog` 早已用
 * `catalogRevision` 做版本化重播种，技能侧缺失同一机制。
 *
 * 修复采用**内容差异比较**（而非版本号常量）：自愈，不会因忘记递增版本而失效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentSkillBuiltinReseedTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: AgentSkillRepository
    private lateinit var dao: AgentSkillDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.agentSkillDao()
        repository = AgentSkillRepository(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun byId(id: String) = dao.observeAll().first().first { it.id == id }

    @Test
    fun `seeds all builtin skills on first run`() = runBlocking {
        repository.ensureInitialized()
        val stored = dao.observeAll().first()
        assertEquals(BuiltinSkills.presets.size, stored.size)
        assertTrue(stored.all { it.isBuiltin })
    }

    @Test
    fun `stale builtin body in the database is refreshed from code`() = runBlocking {
        repository.ensureInitialized()
        val target = BuiltinSkills.presets.first()
        // 模拟"旧版本留下的过期正文"：直接改写库里的内容
        dao.upsert(byId(target.id).copy(systemPrompt = "【过期正文】旧版本内容"))

        repository.ensureInitialized()

        assertEquals(
            "代码侧的新正文必须覆盖库里的过期内容",
            target.systemPrompt,
            byId(target.id).systemPrompt,
        )
    }

    @Test
    fun `user disabled choice survives a content refresh`() = runBlocking {
        repository.ensureInitialized()
        val target = BuiltinSkills.presets.first()
        dao.setEnabled(target.id, false)
        dao.upsert(byId(target.id).copy(systemPrompt = "【过期正文】"))

        repository.ensureInitialized()

        val refreshed = byId(target.id)
        assertEquals("正文应刷新", target.systemPrompt, refreshed.systemPrompt)
        assertFalse("用户手动关掉的技能不得被升级重新打开", refreshed.isEnabled)
    }

    @Test
    fun `no write happens when nothing changed`() = runBlocking {
        repository.ensureInitialized()
        val before = dao.observeAll().first().map { it.copy() }

        repository.ensureInitialized()

        assertEquals("内容一致时不应产生写入", before, dao.observeAll().first())
    }

    @Test
    fun `custom skills are never touched by builtin seeding`() = runBlocking {
        repository.ensureInitialized()
        val custom = AgentSkillEntity(
            id = "custom_abc12345",
            name = "我的技能",
            description = "自建",
            systemPrompt = "自建正文",
            triggerCommand = null,
            iconName = "Code",
            isEnabled = true,
            isBuiltin = false,
            isImmutable = false,
            category = "自定义",
            resourcePath = null,
        )
        dao.upsert(custom)

        repository.ensureInitialized()

        assertEquals("自建技能不得被播种逻辑改写", custom, byId("custom_abc12345"))
    }
}
