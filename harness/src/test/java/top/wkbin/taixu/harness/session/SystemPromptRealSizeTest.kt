package top.wkbin.taixu.harness.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.logging.SensitiveDataRedactor
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.database.AgentSubagentRepository
import top.wkbin.taixu.core.database.AgencyAgentCatalogLoader
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.McpServerRepository
import top.wkbin.taixu.core.database.RoomAgentContextRepository
import top.wkbin.taixu.core.database.RoomHarnessRuntimeRepository
import top.wkbin.taixu.core.security.SecretManager
import top.wkbin.taixu.core.tools.ToolRegistry
import top.wkbin.taixu.core.tools.ToolRepository
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.ToolCallMode
import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.harness.prompt.PrivilegeSectionRenderer
import top.wkbin.taixu.harness.prompt.PromptAssetLoader
import top.wkbin.taixu.harness.prompt.PromptRouter
import top.wkbin.taixu.harness.prompt.SystemPromptBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemPromptRealSizeTest {

    private lateinit var builder: SystemPromptBuilder
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val runtimeRepo = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        val json = Json { ignoreUnknownKeys = true }
        val agentPrefs = AgentPreferences(SettingsDataStore(context, SecretManager()))
        val promptAssets = PromptAssetLoader(context)
        tempDir = File(System.getProperty("java.io.tmpdir"), "taixu-sp-realsize-${System.nanoTime()}")
        builder = SystemPromptBuilder(
            context = context,
            settingsDataStore = agentPrefs,
            skillRepository = AgentSkillRepository(database.agentSkillDao()),
            toolRepository = ToolRepository(database.toolDao(), ToolRegistry(context, OkHttpClient(), logger)),
            agentContextDao = RoomAgentContextRepository(database.agentContextDao()),
            subagentRepository = AgentSubagentRepository(
                database.agentSubagentDao(),
                AgencyAgentCatalogLoader(context, json),
            ),
            mcpServerRepository = McpServerRepository(database.mcpServerDao(), SecretManager()),
            promptAssets = promptAssets,
            fileAccess = WorkspaceFileAccess(tempDir),
            privilegeRenderer = PrivilegeSectionRenderer { "" },
            promptRouter = PromptRouter(promptAssets),
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun buildRealSystem_underMaxTokens() {
        val system = runBlocking { builder.build(workspacePath = "", toolCallMode = ToolCallMode.NATIVE) }
        val tokens = ContextWindowPolicy.estimateTokens(system)
        val budget = ContextWindowPolicy.MAX_CONTEXT_BUDGET
        val maxTokens = (budget * 0.60).toInt().coerceAtLeast(512)
        println("BUILD_REAL_SYSTEM estimateTokens=$tokens chars=${system.length} maxTokens=$maxTokens truncated=${tokens > maxTokens}")
        assertTrue(
            "build() 出 system $tokens tokens，超过 maxTokens $maxTokens（会被 fitSystemPrompt 截断）",
            tokens < maxTokens
        )
    }
}
