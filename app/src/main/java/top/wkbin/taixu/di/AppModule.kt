package top.wkbin.taixu.di

import android.content.Context
import androidx.room.Room
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.MIGRATION_27_28
import top.wkbin.taixu.core.database.MIGRATION_28_29
import top.wkbin.taixu.core.database.MIGRATION_30_31
import top.wkbin.taixu.core.database.MIGRATION_31_32
import top.wkbin.taixu.core.database.MIGRATION_33_34
import top.wkbin.taixu.core.database.MIGRATION_34_35
import top.wkbin.taixu.core.database.MIGRATION_35_36
import top.wkbin.taixu.core.database.MIGRATION_36_37
import top.wkbin.taixu.core.database.MIGRATION_37_38
import top.wkbin.taixu.core.database.MIGRATION_38_39
import top.wkbin.taixu.core.database.MIGRATION_39_40
import top.wkbin.taixu.core.database.MIGRATION_40_41
import top.wkbin.taixu.core.database.MIGRATION_41_42
import top.wkbin.taixu.core.database.MIGRATION_42_43
import top.wkbin.taixu.core.database.MIGRATION_43_44
import top.wkbin.taixu.core.database.MIGRATION_44_45
import top.wkbin.taixu.core.database.MIGRATION_45_46
import top.wkbin.taixu.core.database.MIGRATION_46_47
import top.wkbin.taixu.core.database.MIGRATION_47_48
import top.wkbin.taixu.core.database.WorkflowDao
import top.wkbin.taixu.core.database.BuildScriptDao
import top.wkbin.taixu.core.database.task.AgentTaskDao
import top.wkbin.taixu.core.database.ToolDao
import top.wkbin.taixu.core.database.InstallLogDao
import top.wkbin.taixu.core.database.InstallTaskDao
import top.wkbin.taixu.core.database.RuntimeDao
import top.wkbin.taixu.core.database.HarnessSessionDao
import top.wkbin.taixu.core.database.AiModelDao
import top.wkbin.taixu.core.database.WorkspaceDao
import top.wkbin.taixu.core.database.TerminalSessionDao
import top.wkbin.taixu.core.database.AgentSubagentDao
import top.wkbin.taixu.core.database.AgentSkillDao
import top.wkbin.taixu.core.database.McpServerDao
import top.wkbin.taixu.core.database.StorageMountBindingDao
import top.wkbin.taixu.core.database.ToolSettingsDao
import top.wkbin.taixu.core.database.AgentApprovalDao
import top.wkbin.taixu.core.database.QuickPhraseDao
import top.wkbin.taixu.core.database.HarnessRuntimeDao
import top.wkbin.taixu.core.database.AndroidAppDao
import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.core.tools.RuntimeManager
import top.wkbin.taixu.core.tools.RuntimeManagerImpl
import top.wkbin.taixu.core.tools.DependencyManager
import top.wkbin.taixu.core.tools.DependencyManagerImpl
import top.wkbin.taixu.runtime.shell.ProcessRegistry
import top.wkbin.taixu.runtime.shell.ProcessRegistryImpl
import top.wkbin.taixu.core.network.HttpClientProvider
import top.wkbin.taixu.core.network.FileDownloader
import top.wkbin.taixu.core.network.ResumableFileDownloader
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.LinuxRuntimeImpl
import top.wkbin.taixu.runtime.shell.ProcessShellExecutor
import top.wkbin.taixu.runtime.shell.ShellExecutor
import top.wkbin.taixu.runtime.pty.PtyManager
import top.wkbin.taixu.runtime.pty.NativePtyManager
import top.wkbin.taixu.runtime.service.LocalServiceLauncher
import top.wkbin.taixu.runtime.service.LocalServiceLauncherImpl
import top.wkbin.taixu.service.AgentForegroundLauncherImpl
import top.wkbin.taixu.harness.AgentForegroundLauncher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // 请求/存储时省略 null 字段：未配置的 reasoning_content 不会发给非推理模型
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "taixu.db")
            .addMigrations(MIGRATION_27_28, MIGRATION_28_29, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides
    @Singleton
    fun provideToolDao(database: AppDatabase): ToolDao = database.toolDao()

    @Provides
    @Singleton
    fun provideInstallLogDao(database: AppDatabase): InstallLogDao = database.installLogDao()

    @Provides
    @Singleton
    fun provideInstallTaskDao(database: AppDatabase): InstallTaskDao = database.installTaskDao()

    @Provides
    @Singleton
    fun provideWorkflowDao(database: AppDatabase): WorkflowDao = database.workflowDao()

    @Provides
    @Singleton
    fun provideRuntimeDao(database: AppDatabase): RuntimeDao = database.runtimeDao()

    @Provides
    @Singleton
    fun provideHarnessSessionDao(database: AppDatabase): HarnessSessionDao = database.harnessSessionDao()

    @Provides
    @Singleton
    fun provideAiModelDao(database: AppDatabase): AiModelDao = database.aiModelDao()

    @Provides
    @Singleton
    fun provideWorkspaceDao(database: AppDatabase): WorkspaceDao = database.workspaceDao()

    @Provides
    @Singleton
    fun provideTerminalSessionDao(database: AppDatabase): TerminalSessionDao = database.terminalSessionDao()

    @Provides
    @Singleton
    fun provideAgentContextDao(database: AppDatabase): top.wkbin.taixu.core.database.AgentContextDao = database.agentContextDao()

    @Provides
    @Singleton
    fun provideAgentSubagentDao(database: AppDatabase): AgentSubagentDao = database.agentSubagentDao()

    @Provides
    @Singleton
    fun provideMcpServerDao(database: AppDatabase): McpServerDao = database.mcpServerDao()

    @Provides
    @Singleton
    fun provideAgentSkillDao(database: AppDatabase): AgentSkillDao = database.agentSkillDao()

    @Provides
    @Singleton
    fun provideStorageMountBindingDao(database: AppDatabase): StorageMountBindingDao = database.storageMountBindingDao()

    @Provides
    @Singleton
    fun provideToolSettingsDao(database: AppDatabase): ToolSettingsDao = database.toolSettingsDao()

    @Provides
    @Singleton
    fun provideAgentApprovalDao(database: AppDatabase): AgentApprovalDao = database.agentApprovalDao()

    @Provides
    @Singleton
    fun provideQuickPhraseDao(database: AppDatabase): QuickPhraseDao = database.quickPhraseDao()

    @Provides
    @Singleton
    fun provideHarnessRuntimeDao(database: AppDatabase): HarnessRuntimeDao = database.harnessRuntimeDao()

    @Provides
    @Singleton
    fun provideAndroidAppDao(database: AppDatabase): AndroidAppDao = database.androidAppDao()

    @Provides
    @Singleton
    fun provideBuildScriptDao(database: AppDatabase): BuildScriptDao = database.buildScriptDao()

    @Provides
    @Singleton
    fun provideAgentTaskDao(database: AppDatabase): AgentTaskDao = database.agentTaskDao()

    @Provides
    @Singleton
    fun provideWorkspaceFileAccess(pathManager: top.wkbin.taixu.runtime.RuntimePathManager): WorkspaceFileAccess =
        WorkspaceFileAccess(pathManager.workspaceDir)

    /** checkpoint 快照落盘到应用私有目录（linux-runtime/checkpoints/<sessionId>/），模型不可见。 */
    @Provides
    @Singleton
    fun provideCheckpointStore(
        pathManager: top.wkbin.taixu.runtime.RuntimePathManager,
    ): top.wkbin.taixu.harness.checkpoint.CheckpointStore =
        top.wkbin.taixu.harness.checkpoint.CheckpointStore().apply {
            persistence = top.wkbin.taixu.harness.checkpoint.FileCheckpointPersistence(
                java.io.File(pathManager.baseDir, "checkpoints"),
            )
        }

    @Provides
    @Singleton
    fun provideRuntimeManager(impl: RuntimeManagerImpl): RuntimeManager = impl

    @Provides
    @Singleton
    fun provideDependencyManager(impl: DependencyManagerImpl): DependencyManager = impl

    @Provides
    @Singleton
    fun provideProcessRegistry(impl: ProcessRegistryImpl): ProcessRegistry = impl

    @Provides
    @Singleton
    fun provideOkHttpClient(provider: HttpClientProvider): OkHttpClient = provider.create()

    @Provides
    @Singleton
    fun provideKtorHttpClient(
        provider: HttpClientProvider,
        okHttpClient: OkHttpClient,
    ): HttpClient = provider.createKtorClient(okHttpClient)

    @Provides
    @Singleton
    fun provideFileDownloader(impl: ResumableFileDownloader): FileDownloader = impl

    @Provides
    @Singleton
    fun provideShellExecutor(impl: ProcessShellExecutor): ShellExecutor = impl

    @Provides
    @Singleton
    fun providePtyManager(impl: NativePtyManager): PtyManager = impl

    @Provides
    @Singleton
    fun provideLinuxRuntime(impl: LinuxRuntimeImpl): LinuxRuntime = impl

    @Provides
    @Singleton
    fun provideLocalServiceLauncher(impl: LocalServiceLauncherImpl): LocalServiceLauncher = impl

    @Provides
    @Singleton
    fun provideAgentForegroundLauncher(impl: AgentForegroundLauncherImpl): AgentForegroundLauncher = impl
}


