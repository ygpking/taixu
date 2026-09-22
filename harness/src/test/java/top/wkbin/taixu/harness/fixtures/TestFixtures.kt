package top.wkbin.taixu.harness.fixtures

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.RoomHarnessRuntimeRepository
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.projection.LiveMessagePort

/**
 * [LiveMessagePort] 的可复用 fake：把 append/publishPersisted 都记下来供断言。
 *
 * 原先内嵌在 `SessionModelSwitcherTest` 里，抽出来供顾问/会话生命周期类测试共用。
 */
class RecordingPort : LiveMessagePort {
    val appended = mutableListOf<Pair<String, HarnessMessage>>()

    override suspend fun append(sessionId: String, message: HarnessMessage) {
        appended += sessionId to message
    }

    override suspend fun publishPersisted(sessionId: String, message: HarnessMessage) {
        append(sessionId, message)
    }

    override fun snapshot(sessionId: String): List<HarnessMessage> = emptyList()

    fun messagesFor(sessionId: String): List<HarnessMessage> =
        appended.filter { it.first == sessionId }.map { it.second }
}

/**
 * in-memory Room 装配 helper。
 *
 * `ApiContextAssemblerTest` 与 `SessionModelSwitcherTest` 原先各写一遍同样的三行；
 * 抽出来后新增集成测试只需一行。
 */
object InMemoryDb {

    fun create(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }
}

/** 便捷：从 database 直接拿运行时仓储。 */
fun AppDatabase.runtimeRepository(): RoomHarnessRuntimeRepository =
    RoomHarnessRuntimeRepository(harnessRuntimeDao())
