package top.wkbin.taixu.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 模型「独占激活」的事务收敛回归测试。
 *
 * 缺陷：三处调用点原先各自分两步写 `clearActive()` → `setActive(id)` / `upsert()`，无事务。
 * 两步之间若进程被杀、协程被取消、或另一处并发写入插入，就会停在「全部非活跃」中间态：
 * `activeModel()` 从此返回 **null**，Harness 取不到默认模型（表现为模型选择被重置/回退内置），
 * 而 `AiProfileWriter` 里 `existing.none { it.isActive }` 的分支也会走上不同路径改写数据。
 * 收进 `@Transaction` 后两步原子可见。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiModelActivationTransactionTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: AiModelDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.aiModelDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun all(): List<AiModelEntity> = dao.observeAll().first()

    private fun model(id: String, active: Boolean = false, createdAt: Long = 1L) =
        AiModelEntity(
            id = id,
            name = "model-$id",
            provider = "openai",
            model = "gpt-x",
            baseUrl = "https://example.invalid",
            isActive = active,
            createdAt = createdAt,
        )

    @Test
    fun `activateOnly leaves exactly one active model`() = runBlocking {
        dao.upsert(model("a", active = true, createdAt = 1L))
        dao.upsert(model("b", createdAt = 2L))

        dao.activateOnly("b")

        assertEquals("b", requireNotNull(dao.activeModel()).id)
        assertEquals(1, all().count { it.isActive })
    }

    /**
     * 目标 id 不存在时必须**不清空**已有激活项。
     * 旧的两步写实现会先 clearActive() 再 setActive(missing) ——
     * UPDATE ... WHERE id='missing' 影响 0 行，于是最终 activeModel() == null 且无法自愈。
     */
    @Test
    fun `activateOnly with a missing id keeps the previous active model`() = runBlocking {
        dao.upsert(model("a", active = true, createdAt = 1L))

        dao.activateOnly("missing-id")

        assertEquals("目标不存在时不得清空既有激活项", "a", dao.activeModel()?.id)
        assertEquals(1, all().count { it.isActive })
    }

    @Test
    fun `activateExclusively writes the profile and clears the previous active in one shot`() = runBlocking {
        dao.upsert(model("a", active = true, createdAt = 1L))

        dao.activateExclusively(model("local-b", active = true, createdAt = 2L))

        assertEquals("local-b", requireNotNull(dao.activeModel()).id)
        assertEquals(1, all().count { it.isActive })
        assertFalse(all().first { it.id == "a" }.isActive)
    }

    @Test
    fun `repository forwards both transactional helpers`() = runBlocking {
        val repository = RoomAiModelRepository(dao)
        repository.upsert(model("a", active = true, createdAt = 1L))
        repository.upsert(model("b", createdAt = 2L))

        repository.activateOnly("b")
        assertEquals("b", repository.activeModel()?.id)

        repository.activateExclusively(model("c", active = true, createdAt = 3L))
        assertEquals("c", repository.activeModel()?.id)
        assertEquals(1, all().count { it.isActive })
    }
}
