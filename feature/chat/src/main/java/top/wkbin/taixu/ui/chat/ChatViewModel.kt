package top.wkbin.taixu.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.taixu.core.tools.AiProfileWriter
import top.wkbin.taixu.core.model.ExecutionMode
import top.wkbin.taixu.core.model.McpConnectionState
import top.wkbin.taixu.core.model.ApprovalMode
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.database.McpServerRepository
import top.wkbin.taixu.core.database.AgentApprovalRepository
import top.wkbin.taixu.core.database.AgentApprovalRequestEntity
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.datastore.GitPreferences
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.runtime.debug.DebugActionBus
import top.wkbin.taixu.runtime.sandbox.SandboxTextExtractor
import top.wkbin.taixu.ui.chat.git.GitPanelController
import top.wkbin.taixu.harness.HarnessLoop
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.PendingMessage
import top.wkbin.taixu.harness.QueuedPrompt
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.ContextUsageBreakdown
import top.wkbin.taixu.harness.events.HarnessEvent
import top.wkbin.taixu.harness.events.HarnessEventBus
import top.wkbin.taixu.harness.workflow.ProactiveWorkflowAdvisor
import top.wkbin.taixu.harness.workflow.ProactiveWorkflowSuggestion
import top.wkbin.taixu.harness.mcp.McpManager
import top.wkbin.taixu.harness.queue.PromptQueue
import top.wkbin.taixu.harness.session.ConversationBranch
import top.wkbin.taixu.harness.session.ConversationBranchKind
import top.wkbin.taixu.harness.session.LaneManager
import top.wkbin.taixu.runtime.WorkspaceManager
import top.wkbin.taixu.runtime.WorkspaceProject
import top.wkbin.taixu.core.tools.AgentModelDiscovery
import top.wkbin.taixu.core.tools.AgentProviderCatalog
import top.wkbin.taixu.core.tools.ProviderEndpointPolicy
import top.wkbin.taixu.core.tools.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import top.wkbin.taixu.feature.chat.R
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import top.wkbin.taixu.runtime.terminal.TerminalSessionManager

private const val MAX_RUNTIME_EVENTS = 160
private const val TAG = "ChatViewModel"
private const val KEY_INPUT_DRAFT = "chat_input_draft"

data class SubagentResultUiState(
    val sessionId: String,
    val branch: ConversationBranch,
    val messages: List<HarnessMessage> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

data class WorkflowLaunchRequest(
    val workflowId: String?,
    val projectName: String,
    val initialVariables: Map<String, String> = emptyMap(),
)

/** 空会话首屏的权限感知引导档位；决定开场提示卡的文案与色调。 */
enum class OnboardingPrivilege { SANDBOX, SANDBOX_UNLOCKABLE, SHIZUKU_READY, ROOT_READY }

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val harnessLoop: HarnessLoop,
    private val sessionDao: HarnessSessionRepository,
    private val aiModelDao: AiModelRepository,
    private val workspaceManager: WorkspaceManager,
    private val settingsDataStore: AgentPreferences,
    private val linuxRuntime: top.wkbin.taixu.runtime.LinuxRuntime,
    private val terminalSessionManager: TerminalSessionManager,
    private val mcpManager: McpManager,
    private val agentSkillRepository: AgentSkillRepository,
    private val mcpServerRepository: McpServerRepository,
    private val approvalRepository: AgentApprovalRepository,
    private val agentContextDao: top.wkbin.taixu.core.database.AgentContextRepository,
    private val compactionManager: top.wkbin.taixu.harness.compaction.CompactionManager,
    private val quickPhraseRepository: top.wkbin.taixu.core.database.QuickPhraseRepository,
    private val laneManager: LaneManager,

    private val eventBus: HarnessEventBus,
    private val proactiveWorkflowAdvisor: ProactiveWorkflowAdvisor,
    private val modelDiscovery: AgentModelDiscovery,
    private val providerCatalog: AgentProviderCatalog,
    private val providerRepository: ProviderRepository,
    private val profileWriter: top.wkbin.taixu.core.tools.AiProfileWriter,
    private val privilegeManager: top.wkbin.taixu.runtime.privilege.PrivilegeManager,
    private val pathManager: top.wkbin.taixu.runtime.RuntimePathManager,
    private val workflowRepository: top.wkbin.taixu.core.database.WorkflowRepository,
    private val providerClient: top.wkbin.taixu.harness.ProviderClient,
    private val gitPreferences: top.wkbin.taixu.core.datastore.GitPreferences,
    private val debugActionBus: top.wkbin.taixu.runtime.debug.DebugActionBus,
    private val textExtractor: top.wkbin.taixu.runtime.sandbox.SandboxTextExtractor,
    private val fullSettingsStore: top.wkbin.taixu.core.datastore.SettingsDataStore,
) : ViewModel() {
    private val _workflowLaunchRequests = kotlinx.coroutines.flow.MutableSharedFlow<WorkflowLaunchRequest>(extraBufferCapacity = 2)
    val workflowLaunchRequests: kotlinx.coroutines.flow.SharedFlow<WorkflowLaunchRequest> = _workflowLaunchRequests
    private val _workflowSuggestions = MutableStateFlow<List<ProactiveWorkflowSuggestion>>(emptyList())
    val workflowSuggestions: StateFlow<List<ProactiveWorkflowSuggestion>> = _workflowSuggestions.asStateFlow()

    /**
     * 模型回复里引用的沙箱绝对路径（如 /workspace/xxx.jpg）到宿主真实目录的映射，
     * 供聊天媒体渲染把 PRoot 内路径翻译成 Android 可读文件。
     */
    val sandboxHostRoots: Map<String, java.io.File> = mapOf(
        "workspace" to pathManager.workspaceDir,
        "attachments" to pathManager.attachmentsDir,
    )

    /** 空会话首屏权限感知引导：按实际特权状态给出不同玩法提示。 */
    val privilegeOnboarding: StateFlow<OnboardingPrivilege?> =
        flow { emit(privilegeManager.getPrivilegeInfo()) }
            .map { info ->
                when {
                    info.mode == ExecutionMode.ROOT && info.modeActive -> OnboardingPrivilege.ROOT_READY
                    info.mode == ExecutionMode.SHIZUKU && info.modeActive -> OnboardingPrivilege.SHIZUKU_READY
                    info.shizukuAvailable || info.rootAvailable -> OnboardingPrivilege.SANDBOX_UNLOCKABLE
                    else -> OnboardingPrivilege.SANDBOX
                }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)


    private val _eventHistory = MutableStateFlow<Map<String, List<HarnessEvent>>>(emptyMap())
    private val _permissionRequests = kotlinx.coroutines.flow.MutableSharedFlow<HarnessEvent.PermissionRequired>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val permissionRequests: kotlinx.coroutines.flow.SharedFlow<HarnessEvent.PermissionRequired> = _permissionRequests

    init {
        viewModelScope.launch {
            quickPhraseRepository.ensureInitialized()
            workflowRepository.ensureBuiltins()
        }
        viewModelScope.launch {
            eventBus.events.collect { event ->
                _eventHistory.value = _eventHistory.value.toMutableMap().apply {
                    this[event.sessionId] = (this[event.sessionId].orEmpty() + event).takeLast(MAX_RUNTIME_EVENTS)
                }
                if (event is HarnessEvent.PermissionRequired) {
                    _permissionRequests.tryEmit(event)
                }
            }
        }
        viewModelScope.launch {
            proactiveWorkflowAdvisor.observeSuggestions().collect { suggestion ->
                _workflowSuggestions.update { current ->
                    (listOf(suggestion) + current.filterNot { it.workflowId == suggestion.workflowId }).take(3)
                }
            }
        }
        // Debug 广播总线（adb 广播 E2E 验证链路，第3项 Git 工作台）：DebugReceiver 只 emit，
        // 真正执行在这里。历史轮 DebugActionBus 建好但无人消费 → 本轮补齐，否则 adb 验证全空转。
        viewModelScope.launch {
            debugActionBus.flow.collect { action ->
                when (action) {
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.CloneRepo -> git.gitClone(action.url)
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.Diagnostic -> {
                        viewModelScope.launch(Dispatchers.IO) {
                            val r = runCatching {
                                linuxRuntime.execute(
                                    top.wkbin.taixu.runtime.shell.ShellCommand(
                                        commandLine = action.command + " 2>&1",
                                        workingDirectory = "/root",
                                        timeoutMs = 300_000L,
                                    ),
                                )
                            }.getOrNull()
                            val out = ((r?.stdout ?: "") + "\n" + (r?.stderr ?: "")).trim().take(2000)
                            android.util.Log.i("TaixuDiag", "CMD=${action.command} → EXIT=${r?.exitCode} OUT=$out")
                            git.reportDebug("诊断：exit=${r?.exitCode}\n$out")
                        }
                    }
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.SwitchWorkspace -> {
                        harnessLoop.debugSetWorkspace(action.path)
                        git.reportDebug("已切工作区到 ${action.path}")
                        git.refreshGitStatus()
                    }
                    top.wkbin.taixu.runtime.debug.DebugActionBus.Action.RefreshStatus -> git.refreshGitStatus()
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.AddCred ->
                        git.addGitCredential(action.name, action.host, action.user, action.token)
                    top.wkbin.taixu.runtime.debug.DebugActionBus.Action.ClearCreds -> git.clearCredentials()
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.VerifyCred ->
                        git.verifyGitCredential(action.id)
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.FetchRepos ->
                        git.fetchUserRepos(action.host)
                    top.wkbin.taixu.runtime.debug.DebugActionBus.Action.AiGenerateCommit ->
                        git.aiGenerateCommitMessage()
                    top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitPull -> git.gitPull()
                    top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitPush -> git.gitPush()
                    top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitStash -> git.gitStash()
                    top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitStashPop -> git.gitStashPop()
                    top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitRevertAll -> git.gitRevertAllUnstaged()
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitRenameBranch ->
                        git.gitRenameBranch(action.old, action.new)
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitDeleteRemote ->
                        git.gitDeleteRemoteBranch(action.name)
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitCheckout ->
                        git.gitCheckout(action.branch)
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitStageAll ->
                        if (action.on) git.gitStageAll() else git.gitUnstageAll()
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitCommit ->
                        git.gitCommit(action.message)
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitCreateTag ->
                        git.gitCreateTag(action.name)
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitPushTag ->
                        git.gitPushTag(action.name)
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitDeleteTagLocal ->
                        git.gitDeleteTag(action.name)
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitDeleteTagRemote ->
                        git.gitDeleteRemoteTag(action.name)
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.GitRaw -> {
                        viewModelScope.launch(Dispatchers.IO) {
                            val out = git.rawRead(action.cmd)
                            android.util.Log.i("TaixuDiag", "GitRaw '${action.cmd}' → $out")
                            git.reportDebug("GitRaw:\n$out")
                        }
                    }
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.ExtractText -> {
                        viewModelScope.launch(Dispatchers.IO) {
                            val r = runCatching { textExtractor.extract(action.guestPath, action.name) }
                            val msg = when (val res = r.getOrNull()) {
                                is top.wkbin.taixu.runtime.sandbox.SandboxTextExtractor.Result.Ok ->
                                    "OK len=${res.text.length} truncated=${res.truncated} 前 200: ${res.text.take(200)}"
                                is top.wkbin.taixu.runtime.sandbox.SandboxTextExtractor.Result.Skipped -> "SKIP ${res.reason}"
                                is top.wkbin.taixu.runtime.sandbox.SandboxTextExtractor.Result.Failed -> "FAIL ${res.error}"
                                null -> "EXC ${r.exceptionOrNull()?.message}"
                            }
                            android.util.Log.i("TaixuDiag", "extract ${action.name} → $msg")
                            git.reportDebug("抽取 ${action.name}: $msg")
                        }
                    }
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.SetProxy -> {
                        viewModelScope.launch(Dispatchers.IO) {
                            fullSettingsStore.setSandboxHttpProxy(action.value)
                            android.util.Log.i("TaixuDiag", "SetProxy = '${action.value}'")
                        }
                    }
                    // SimulateAttachment / CreateProject 暂不接线：
                    // · SimulateAttachment 要求附件有「解析中→✓N字符」状态机，fork 的 ChatAttachment 无该字段
                    //   （fork 走"把路径写进 prompt 让 agent 自己读"的模型），硬接需先改附件模型，超出本轮范围；
                    // · CreateProject 参数（templateId/templateVariables）与 fork WorkspaceManager.createProject
                    //   签名差异较大，且与「Git 可视化工作台」无关，留给工坊任务单独接。
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.SimulateAttachment,
                    is top.wkbin.taixu.runtime.debug.DebugActionBus.Action.CreateProject -> Unit
                }
            }
        }
    }

    val quickPhrases: StateFlow<List<top.wkbin.taixu.core.model.QuickPhrase>> = quickPhraseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeDistroId: StateFlow<String> = linuxRuntime.activeDistroId
    val installedDistros: StateFlow<List<top.wkbin.taixu.core.model.InstalledDistro>> = linuxRuntime.installedDistros

    fun switchDistro(distroId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 先关闭所有旧系统 PTY 会话，再切换发行版
            terminalSessionManager.closeAllSessions()
            linuxRuntime.switchActiveDistro(distroId)
        }
    }

    val messages: StateFlow<List<HarnessMessage>> = harnessLoop.messages
    val running: StateFlow<Boolean> = harnessLoop.running
    val error: StateFlow<String?> = harnessLoop.error
    val status: StateFlow<String?> = harnessLoop.status
    val thinkingLive: StateFlow<Boolean> = harnessLoop.thinkingLive
    val workspace: StateFlow<String> = harnessLoop.workspace
    val projectType: StateFlow<String> = harnessLoop.projectType

    /**
     * Git 可视化工作台控制器（第 3 项搬运）。
     * 逻辑来自万象 Wanxiang `ChatViewModel` 内嵌 git 子系统，抽为独立类以隔离风险；
     * UI 通过 `viewModel.git.xxx` 访问，行为与 Wanxiang 版一致。
     */
    val git: GitPanelController = GitPanelController(
        linuxRuntime = linuxRuntime,
        gitPreferences = gitPreferences,
        providerClient = providerClient,
        scope = viewModelScope,
        workspaceProvider = { harnessLoop.workspace.value },
        onSwitchWorkspace = { path -> harnessLoop.debugSetWorkspace(path) },
    )
    /** 基于当前工作区内容自动推荐的 MCP 预设（已启用的已过滤），仅提示不自动启用。 */
    val mcpRecommendations: StateFlow<List<top.wkbin.taixu.harness.mcp.McpWorkspaceRecommender.Recommendation>> =
        harnessLoop.mcpRecommendations

    fun enableMcpRecommendation(presetId: String) = harnessLoop.enableRecommendedMcp(presetId)
    fun dismissMcpRecommendation(presetId: String) = harnessLoop.dismissMcpRecommendation(presetId)
    fun dismissWorkflowSuggestion(workflowId: String) {
        _workflowSuggestions.update { suggestions -> suggestions.filterNot { it.workflowId == workflowId } }
    }

    fun launchWorkflowSuggestion(suggestion: ProactiveWorkflowSuggestion) {
        dismissWorkflowSuggestion(suggestion.workflowId)
        _workflowLaunchRequests.tryEmit(
            WorkflowLaunchRequest(suggestion.workflowId, suggestion.projectName, suggestion.initialVariables),
        )
    }
    /** 运行中排队的待发送消息（当前任务结束后自动接续）。 */
    val pendingMessages: StateFlow<List<PendingMessage>> = harnessLoop.pendingMessages
    val queuedPrompts: StateFlow<List<QueuedPrompt>> = harnessLoop.queuedPrompts

    private val _sendMode = MutableStateFlow(ComposerSendMode.NEXT_RUN)
    val sendMode: StateFlow<ComposerSendMode> = _sendMode.asStateFlow()

    val runtimeEvents: StateFlow<List<HarnessEvent>> = combine(
        harnessLoop.currentSessionId,
        _eventHistory,
        messages,
    ) { sessionId, history, msgList ->
        val live = history[sessionId].orEmpty()
        mergeHistoricalAndLiveEvents(sessionId, msgList, live)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _branchRefresh = MutableStateFlow(0)
    private val branchMessageRevision = messages.map { list -> list.size to list.lastOrNull()?.id }.distinctUntilChanged()
    // 子智能体在独立 lane 中执行时主会话消息不变，用运行事件驱动分支重投影，
    // 这样运行中也能在协同卡片里点进子 lane 看实时进展。
    private val branchEventRevision = runtimeEvents.map { events ->
        events.size to (events.lastOrNull()?.hashCode() ?: 0)
    }.distinctUntilChanged()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val branches: StateFlow<List<ConversationBranch>> = combine(
        harnessLoop.currentSessionId,
        branchMessageRevision,
        _branchRefresh,
        branchEventRevision,
    ) { sessionId, _, _, _ -> sessionId }.mapLatest { sessionId ->
        if (sessionId.isBlank()) emptyList() else runCatching { laneManager.branches(sessionId) }.getOrDefault(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前选中的会话 ID */
    val currentSessionId: StateFlow<String> = harnessLoop.currentSessionId
    /** 所有会话的多 Agent 并发运行状态映射 (IDLE / RUNNING / COMPLETED / FAILED) */
    val sessionRunStates: StateFlow<Map<String, top.wkbin.taixu.core.model.SessionRunState>> = harnessLoop.sessionRunStates
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pendingApprovals: StateFlow<List<AgentApprovalRequestEntity>> = harnessLoop.currentSessionId.flatMapLatest { sessionId ->
        if (sessionId.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else approvalRepository.pendingForSession(sessionId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前会话的活跃结构化任务规划（模型通过 plan 工具写入的 AgentPlanEntity）。
     * 以 currentSessionId + 运行状态为键重新读取：一轮执行内状态多次变化，
     * 借此近似实时刷新看板进度；无规划或非活跃时为 null。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activePlan: StateFlow<top.wkbin.taixu.core.database.AgentPlanEntity?> =
        combine(harnessLoop.currentSessionId, harnessLoop.status) { sessionId, _ -> sessionId }
            .distinctUntilChanged()
            .flatMapLatest { sessionId ->
                kotlinx.coroutines.flow.flow {
                    emit(if (sessionId.isBlank()) null else agentContextDao.getActivePlan(sessionId)?.takeIf { it.status == "active" })
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 当前会话最近一次上下文压缩的快照（折叠条数 + 摘要预览）。
     * 会话从未压缩时为 null——UI 据此隐藏提示横幅。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeCompaction: StateFlow<top.wkbin.taixu.harness.compaction.CompactionSnapshot?> =
        combine(harnessLoop.currentSessionId, harnessLoop.status) { sessionId, _ -> sessionId }
            .distinctUntilChanged()
            .flatMapLatest { sessionId ->
                kotlinx.coroutines.flow.flow {
                    emit(if (sessionId.isBlank()) null else compactionManager.latestSnapshot(sessionId))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 全量长期记忆（memory 工具写入），供记忆抽屉管理与模型上下文核对。 */
    val memories: StateFlow<List<top.wkbin.taixu.core.database.AgentMemoryEntity>> =
        agentContextDao.observeAllMemories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val scratchpadRefresh = MutableStateFlow(0)

    /** 当前会话的草稿便签；随运行状态变化与手动刷新重建。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val scratchpads: StateFlow<List<top.wkbin.taixu.core.database.AgentScratchpadEntity>> =
        combine(
            harnessLoop.currentSessionId,
            harnessLoop.status,
            scratchpadRefresh,
        ) { sessionId, _, _ -> sessionId }
            .distinctUntilChanged()
            .flatMapLatest { sessionId ->
                flow {
                    emit(if (sessionId.isBlank()) emptyList() else agentContextDao.listScratchpads(sessionId))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteMemory(id: String) {
        viewModelScope.launch { agentContextDao.deleteMemoryById(id) }
    }

    fun deleteScratchpad(key: String) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            agentContextDao.deleteScratchpad(sessionId, key)
            scratchpadRefresh.value++
        }
    }

    fun clearScratchpads() {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            agentContextDao.clearScratchpads(sessionId)
            scratchpadRefresh.value++
        }
    }

    fun resolveApproval(requestId: String, approved: Boolean) {
        harnessLoop.resolveApproval(requestId, approved)
    }

    val sessions: StateFlow<List<HarnessSessionEntity>> = sessionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setCurrentSessionApprovalMode(mode: ApprovalMode) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            sessionDao.setApprovalMode(sessionId, mode.id, System.currentTimeMillis())
        }
    }

    val models: StateFlow<List<AiModelEntity>> = aiModelDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val workspaces: StateFlow<List<WorkspaceProject>> = workspaceManager.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 思考过程块是否默认展开（持久化，重启后保留）。 */
    val thinkingExpanded: StateFlow<Boolean> = settingsDataStore.thinkingExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setThinkingExpanded(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setThinkingExpanded(value) }
    }

    // 输入草稿：同步写入 SavedStateHandle，进程重建 / 旋转后可恢复
    private val _input = MutableStateFlow(savedStateHandle.get<String>(KEY_INPUT_DRAFT) ?: "")
    val input: StateFlow<String> = _input.asStateFlow()

    /** 统一输入写入入口：StateFlow 供组合使用，SavedStateHandle 供状态恢复使用 */
    private fun setInput(value: String) {
        _input.value = value
        savedStateHandle[KEY_INPUT_DRAFT] = value
    }

    val activeSkills: StateFlow<List<top.wkbin.taixu.core.model.AgentSkill>> = agentSkillRepository.activeSkills
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allSkills: StateFlow<List<top.wkbin.taixu.core.model.AgentSkill>> = agentSkillRepository.allSkills
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mcpServers: StateFlow<List<top.wkbin.taixu.core.model.McpServerConfig>> = mcpServerRepository.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前会话上下文用量的 UI 估算。Harness 发请求时会用同一字符/token 近似值再做最终压缩，
     * 因此这里明确是预估值，而不是 provider 返回的精确 tokenizer 计数。
     */
    val contextUsage: StateFlow<ContextUsage> = combine(
        messages,
        models,
        allSkills,
        mcpServers,
        settingsDataStore.contextBudgetTokens,
    ) { currentMessages, currentModels, skills, mcps, defaultBudget ->
        ContextUsageInputs(
            currentMessages = currentMessages,
            activeModel = currentModels.firstOrNull { it.isActive },
            skills = skills,
            mcps = mcps,
            defaultBudget = defaultBudget,
        )
    }.combine(settingsDataStore.contextCompactionEnabled) { inputs, compactionEnabled ->
        val activeModel = inputs.activeModel
        val pureChat = activeModel?.pureChatMode == true
        val toolDisabled = pureChat || activeModel?.toolCallMode.equals("disabled", ignoreCase = true)

        val systemPromptTokens = if (pureChat) 0 else ContextWindowPolicy.DEFAULT_SYSTEM_PROMPT_TOKENS
        val toolDefinitionTokens = if (toolDisabled) 0 else ContextWindowPolicy.DEFAULT_NATIVE_TOOL_TOKENS
        val rulesTokens = if (pureChat) 0 else ContextWindowPolicy.DEFAULT_RULES_TOKENS
        val skillTokens = if (pureChat) 0 else inputs.skills.filter { it.isEnabled }.sumOf {
            ContextWindowPolicy.estimateTokens(it.systemPrompt)
        }
        val mcpTokens = if (pureChat) 0 else inputs.mcps.filter { it.isEnabled }.sumOf {
            ContextWindowPolicy.estimateTokens("${it.name}\n${it.description}\n${it.command}\n${it.args.joinToString(" ")}")
        }
        val subagentTokens = if (toolDisabled) 0 else ContextWindowPolicy.DEFAULT_SUBAGENT_TOKENS

        val totalSystemTokens = systemPromptTokens + toolDefinitionTokens + rulesTokens + skillTokens + mcpTokens + subagentTokens
        val budget = (activeModel?.contextTokens ?: inputs.defaultBudget).coerceAtLeast(1)

        val effectiveUsage = ContextWindowPolicy.estimateEffectiveUsage(
            messages = inputs.currentMessages,
            budget = budget,
            systemTokens = totalSystemTokens,
            compactionEnabled = compactionEnabled,
            systemPromptTokens = systemPromptTokens,
            toolDefinitionTokens = toolDefinitionTokens,
            rulesTokens = rulesTokens,
            skillsTokens = skillTokens,
            mcpTokens = mcpTokens,
            subagentTokens = subagentTokens,
        )
        val totalPromptTokens = inputs.currentMessages.filterIsInstance<AssistantText>().mapNotNull { it.promptTokens?.toLong() }.sum()
        val totalCachedTokens = inputs.currentMessages.filterIsInstance<AssistantText>().mapNotNull { it.cachedTokens?.toLong() }.sum()
        val cacheHitPct = if (totalPromptTokens > 0L && totalCachedTokens > 0L) {
            ((totalCachedTokens * 100L) / totalPromptTokens).toInt().coerceIn(1, 100)
        } else null

        ContextUsage(
            usedTokens = effectiveUsage.totalTokens,
            limitTokens = budget,
            systemTokens = totalSystemTokens,
            toolTokens = effectiveUsage.toolTokens,
            conversationTokens = effectiveUsage.conversationTokens,
            compacted = effectiveUsage.keepFromIndex > 0,
            cachedTokens = totalCachedTokens,
            cacheHitRatePercent = cacheHitPct,
            breakdown = effectiveUsage.breakdown,
        )

    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContextUsage())

    /** 各 MCP 服务的实时连通性状态（与 McpManager 共享，聊天挂载面板 / 设置页联动）。 */
    val mcpConnectionStates: StateFlow<Map<String, McpConnectionState>> = mcpManager.connectionStates

    fun refreshMcpConnections() {
        viewModelScope.launch { mcpManager.refreshConnections() }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        viewModelScope.launch {
            agentSkillRepository.setEnabled(skillId, enabled)
        }
    }

    fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            mcpServerRepository.setEnabled(serverId, enabled)
            mcpManager.refreshConnections()
        }
    }

    /** 斜杠指令建议列表（当输入以 / 开头时实时过滤展示，自动合并已激活的专精技能与可用工作流）。 */
    val matchingCommands: StateFlow<List<SlashCommandItem>> = kotlinx.coroutines.flow.combine(
        _input,
        agentSkillRepository.activeSkills,
        workflowRepository.observeDefinitions(),
    ) { text, skills, workflows ->
        if (text.startsWith("/")) SlashCommands.filterCommands(context, text, skills, workflows)
        else emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun skillToMentionItem(skill: top.wkbin.taixu.core.model.AgentSkill): MentionItem = MentionItem(
        id = skill.id,
        name = skill.name,
        description = skill.description,
        category = context.getString(R.string.chat_skill_category),
        type = MentionType.SKILL,
        icon = top.wkbin.taixu.ui.components.RuntimeIconName.Brain,
    )

    private fun mcpToMentionItem(
        mcp: top.wkbin.taixu.core.model.McpServerConfig,
        description: String = mcp.description,
    ): MentionItem = MentionItem(
        id = mcp.id,
        name = mcp.name,
        description = description,
        category = context.getString(R.string.chat_mcp_category),
        type = MentionType.MCP_SERVER,
        icon = top.wkbin.taixu.ui.components.RuntimeIconName.Cpu,
    )

    /** @ 艾特唤醒建议列表（当输入包含 @ 时实时过滤技能与 MCP 插件）。 */
    val matchingMentions: StateFlow<List<MentionItem>> = kotlinx.coroutines.flow.combine(
        _input,
        agentSkillRepository.allSkills,
        mcpServerRepository.servers,
    ) { text, skills, mcps ->
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0) return@combine emptyList()
        val mentionToken = text.substring(atIndex + 1)
        if (mentionToken.any { it.isWhitespace() }) return@combine emptyList()
        val query = mentionToken.lowercase()

        val skillMentions = skills.filter { it.isEnabled }.map(::skillToMentionItem)
        val mcpMentions = mcps.filter { it.isEnabled && !it.isBuiltin }.map { mcp ->
            mcpToMentionItem(mcp, context.getString(R.string.chat_mcp_service_description, mcp.transportType))
        }
        val all = skillMentions + mcpMentions
        if (query.isEmpty()) all
        else all.filter { it.name.lowercase().contains(query) || it.description.lowercase().contains(query) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前会话中明确被用户手动钉选常驻的技能与 MCP ID 集合（默认为空，不默认常驻）。 */
    private val _pinnedMentionIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedMentionIds: StateFlow<Set<String>> = _pinnedMentionIds.asStateFlow()

    /** 当前会话中已钉选常驻的技能与 MCP 列表（默认为空，仅在用户显式钉选后常驻展示并生效）。 */
    val pinnedCapabilities: StateFlow<List<MentionItem>> = kotlinx.coroutines.flow.combine(
        _pinnedMentionIds,
        agentSkillRepository.allSkills,
        mcpServerRepository.servers,
    ) { pinnedIds, skills, mcps ->
        if (pinnedIds.isEmpty()) return@combine emptyList()
        val skillItems = skills
            .filter { it.isEnabled && (it.id in pinnedIds || it.name.lowercase() in pinnedIds) }
            .map(::skillToMentionItem)
        val mcpItems = mcps
            .filter { it.isEnabled && !it.isBuiltin && (it.id in pinnedIds || it.name.lowercase() in pinnedIds) }
            .map(::mcpToMentionItem)
        skillItems + mcpItems
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun togglePinMention(id: String) {
        val target = id.trim().lowercase()
        _pinnedMentionIds.update { current ->
            if (target in current || current.any { it.equals(id, ignoreCase = true) }) {
                current.filterNot { it.equals(id, ignoreCase = true) || it == target }.toSet()
            } else {
                current + target
            }
        }
    }

    fun unpinMention(id: String) {
        val target = id.trim().lowercase()
        _pinnedMentionIds.update { current ->
            current.filterNot { it.equals(id, ignoreCase = true) || it == target }.toSet()
        }
    }

    fun pinMention(id: String) {
        val target = id.trim().lowercase()
        _pinnedMentionIds.update { it + target }
    }

    /** 当前输入框中已挂载的技能与 MCP 标签列表（用于输入框顶部展示高亮双排 Chips）。 */
    val attachedMentions: StateFlow<List<MentionItem>> = kotlinx.coroutines.flow.combine(
        _input,
        agentSkillRepository.allSkills,
        mcpServerRepository.servers,
    ) { text, skills, mcps ->
        if (!text.contains("@")) return@combine emptyList()
        val regex = Regex("""@([^\s@,，:：\n]+)""")
        val matchedNames = regex.findAll(text).map { it.groupValues[1].trim().lowercase() }.toSet()
        if (matchedNames.isEmpty()) return@combine emptyList()

        val matchedSkills = skills
            .filter { skill ->
                skill.isEnabled && (
                    skill.name.lowercase() in matchedNames || skill.id.lowercase() in matchedNames
                    )
            }
            .map(::skillToMentionItem)

        val matchedMcps = mcps
            .filter { mcp ->
                mcp.isEnabled && !mcp.isBuiltin && (
                    mcp.name.lowercase() in matchedNames || mcp.id.lowercase() in matchedNames
                    )
            }
            .map(::mcpToMentionItem)

        matchedSkills + matchedMcps
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _initializing = MutableStateFlow(true)
    val initializing: StateFlow<Boolean> = _initializing.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // 恢复最近会话；没有则新建
            val latest = sessionDao.observeAll().first().firstOrNull()
            if (latest != null) {
                harnessLoop.loadSession(latest.id)
            } else {
                harnessLoop.newSession(context.getString(R.string.chat_new_session))
            }
            _initializing.value = false
        }
    }

    fun onInputChanged(value: String) {
        setInput(value)
    }

    fun applySlashCommand(command: SlashCommandItem) {
        if (command.command == "/clear") {
            createSession(context.getString(R.string.chat_new_session))
            setInput("")
            notifyNewSessionCreated()
        } else {
            setInput(command.template)
        }
    }

    fun applyQuickPhrase(phrase: top.wkbin.taixu.core.model.QuickPhrase) {
        if (phrase.content.trim() == "/clear") {
            createSession(context.getString(R.string.chat_new_session))
            setInput("")
            notifyNewSessionCreated()
        } else {
            setInput(phrase.content)
        }
    }

    /** /clear 会静默重建会话，用 Toast 明确告知用户上下文已重置 */
    private fun notifyNewSessionCreated() {
        Toast.makeText(context, context.getString(R.string.chat_new_session_created), Toast.LENGTH_SHORT).show()
    }

    fun applyMention(item: MentionItem) {
        val text = _input.value
        val atIndex = text.lastIndexOf('@')
        val prefix = if (atIndex >= 0) text.substring(0, atIndex) else text
        // Persist the stable id so names containing spaces or punctuation cannot be
        // truncated by the mention parser; the attached chip still shows the friendly name.
        setInput("${prefix}@${item.id} ")
    }

    /** 从输入框中整块移除某个已挂载的 @能力 标签 */
    fun removeMention(item: MentionItem) {
        val current = _input.value
        // 正则替换 @name 及其后可能跟随的空格
        val updated = current.replace(Regex("""@${Regex.escape(item.name)}\s*"""), "")
            .replace(Regex("""@${Regex.escape(item.id)}\s*"""), "")
            .trimStart()
        setInput(updated)
    }

    fun triggerMentionInput() {
        val current = _input.value
        if (!current.endsWith("@")) {
            setInput(if (current.isBlank()) "@" else "$current @")
        }
    }

    fun setSendMode(mode: ComposerSendMode) {
        _sendMode.value = mode
    }

    private val _pendingAttachments = MutableStateFlow<List<ChatAttachment>>(emptyList())

    /** 待发送附件；处理（复制/压缩/编码）在 IO 线程完成 */
    val pendingAttachments: StateFlow<List<ChatAttachment>> = _pendingAttachments.asStateFlow()

    /** 附件处理中（复制/压缩/编码期间为 true，UI 据此展示加载指示） */
    private val _attachmentsProcessing = MutableStateFlow(false)
    val attachmentsProcessing: StateFlow<Boolean> = _attachmentsProcessing.asStateFlow()

    /** 需要以 Toast 提示用户的轻量通知（附件失败、模型档案已存在等），展示后调用 clearNotice() */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    fun onAttachmentsPicked(uris: List<Uri>, isImage: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _attachmentsProcessing.value = true
            val items = uris.mapNotNull { AttachmentHelper.processUri(context, it, isImage) }
            val failed = uris.size - items.size
            if (failed > 0) {
                _notice.value = context.getString(R.string.chat_attachment_process_failed, failed)
            }
            _pendingAttachments.update { it + items }
            _attachmentsProcessing.value = false
        }
    }

    fun removeAttachment(attachment: ChatAttachment) {
        _pendingAttachments.update { list -> list.filter { it.id != attachment.id } }
    }

    /** 组装附件挂载说明并委托 send() 发送；View 只需在输入框非空或有附件时触发 */
    fun sendFromComposer() {
        val trimmedInput = _input.value.trim()
        val attachments = _pendingAttachments.value
        if (trimmedInput.isBlank() && attachments.isEmpty()) return
        val imageUrls = attachments.mapNotNull { it.base64DataUrl }
        val nonImageFiles = attachments.filter { !it.isImage }
        val fullMessage = buildString {
            if (trimmedInput.isNotBlank()) {
                append(trimmedInput)
            } else if (nonImageFiles.isNotEmpty()) {
                append(context.getString(R.string.chat_attachment_files_prompt))
            } else if (imageUrls.isNotEmpty()) {
                append(context.getString(R.string.chat_attachment_images_prompt))
            }
            if (attachments.isNotEmpty()) {
                append(context.getString(R.string.chat_attachment_mount_header))
                attachments.forEachIndexed { i, att ->
                    val guestPath = att.guestFilePath ?: "/attachments/${att.name}"
                    val kind = context.getString(if (att.isImage) R.string.chat_attachment_image else R.string.chat_attachment_file)
                    append(context.getString(R.string.chat_attachment_line, i + 1, kind, att.name, AttachmentHelper.formatFileSize(att.sizeBytes), guestPath))
                }
                append(context.getString(R.string.chat_attachment_access_hint))
            }
        }
        _pendingAttachments.value = emptyList()
        send(fullMessage, imageUrls)
    }

    fun send(customText: String? = null, imageUrls: List<String> = emptyList()) {
        val rawText = (customText ?: _input.value).trim()
        if (rawText.isBlank() && imageUrls.isEmpty()) return
        WORKFLOW_COMMAND.matchEntire(rawText)?.let { match ->
            setInput("")
            val projectName = workspace.value.trim('/').removePrefix("workspace/").substringBefore('/').takeIf(String::isNotBlank).orEmpty()
            _workflowLaunchRequests.tryEmit(WorkflowLaunchRequest(match.groupValues[1].takeIf(String::isNotBlank), projectName))
            return
        }
        setInput("")

        val pinnedIds = _pinnedMentionIds.value
        val effectiveText = if (pinnedIds.isNotEmpty()) {
            val existingMentions = top.wkbin.taixu.harness.MentionExtractor.parse(rawText)
            val missingPins = pinnedIds.filter { pin ->
                pin.lowercase() !in existingMentions
            }
            if (missingPins.isNotEmpty()) {
                rawText + missingPins.joinToString(prefix = " ", separator = " ") { "@$it" }
            } else {
                rawText
            }
        } else {
            rawText
        }

        if (!running.value) {
            harnessLoop.send(effectiveText, imageUrls = imageUrls)
        } else {
            when (_sendMode.value) {
                ComposerSendMode.STEER -> harnessLoop.steer(effectiveText, imageUrls = imageUrls)
                ComposerSendMode.NEXT_RUN -> harnessLoop.send(effectiveText, imageUrls = imageUrls)
            }
        }
    }

    private companion object {
        val WORKFLOW_COMMAND = Regex("^/wf(?:\\s+([A-Za-z0-9_.-]+))?$")
    }

    /** 创建针对工具安装或沙箱异常的专属自愈会话并立即启动诊断 */
    fun startHealingTask(title: String, prompt: String) {
        viewModelScope.launch {
            harnessLoop.newSession(title = title)
            setInput("")
            harnessLoop.send(prompt)
        }
    }

    /** 重新生成最后一次回复 */
    fun regenerateLast() {
        if (running.value) return
        harnessLoop.regenerateLast()
    }

    fun retryToolCall(toolCallId: String) {
        if (running.value) return
        harnessLoop.retryToolCall(toolCallId)
    }

    fun createBranch(messageId: String, displayName: String) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank() || running.value) return
        viewModelScope.launch {
            runCatching {
                laneManager.createConversationBranch(sessionId, displayName, messageId)
                harnessLoop.activateBranch(messageId, sessionId)
            }
            _branchRefresh.value++
        }
    }

    fun switchBranch(branch: ConversationBranch) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank() || running.value) return
        viewModelScope.launch {
            harnessLoop.activateBranch(branch.leafId, sessionId)
            _branchRefresh.value++
        }
    }

    /** 编辑并重新发送某条用户消息 */
    fun editAndResend(userMessageId: String, newText: String) {
        if (running.value || newText.isBlank()) return
        harnessLoop.truncateAndResend(userMessageId, newText)
    }

    /** 删除单条消息 */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            harnessLoop.deleteMessage(messageId)
        }
    }

    fun stop() = harnessLoop.cancel()

    fun removePendingMessage(index: Int) = harnessLoop.removePendingMessage(index)

    fun removeQueuedPrompt(prompt: QueuedPrompt) {
        val sameQueue = queuedPrompts.value.filter { it.queue == prompt.queue }
        val index = sameQueue.indexOfFirst { it.id == prompt.id }
        if (index >= 0) harnessLoop.removeQueuedPrompt(prompt.queue, index)
    }

    /** 将排队消息转为即时修正（Steer）指令并插入下一轮 */
    fun convertQueuedPromptToSteer(prompt: QueuedPrompt) {
        removeQueuedPrompt(prompt)
        harnessLoop.steer(prompt.message.text, imageUrls = prompt.message.imageUrls)
    }

    /** 编辑排队消息：先移出队列（不发送），把原文回显到输入框，修改后由用户手动发送。 */
    fun editQueuedPrompt(prompt: QueuedPrompt) {
        removeQueuedPrompt(prompt)
        setInput(prompt.message.text)
    }

    fun clearPendingMessages() = harnessLoop.clearPendingMessages()

    fun clearError() = harnessLoop.clearError()

    /** 新建会话（支持自定义标题并关联工作区）。 */
    fun createSession(title: String = "", workspace: String = "", projectType: String = "") {
        viewModelScope.launch {
            harnessLoop.newSession(title.trim().ifBlank { context.getString(R.string.chat_new_session) }, workspace, projectType)
        }
    }

    fun switchSession(id: String) {
        viewModelScope.launch { harnessLoop.loadSession(id) }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch { harnessLoop.deleteSession(id) }
    }

    fun renameSession(id: String, title: String) {
        viewModelScope.launch { harnessLoop.renameSession(id, title) }
    }

    // ---- 撤回到此轮（Checkpoint Rewind） ----

    /**
     * 撤回到 [messageId] 所在用户轮：按 checkpoint 锚点定位轮次，
     * prepare/commit 两段式执行；CONVERSATION/BOTH 会派生回退分支并切换过去。
     */
    fun rewindToMessage(messageId: String, scope: top.wkbin.taixu.harness.checkpoint.RewindScope) {
        viewModelScope.launch(Dispatchers.IO) {
            val sessionId = harnessLoop.currentSessionId.value
            val target = harnessLoop.sessionCheckpoints(sessionId)
                .firstOrNull { it.anchorMessageId == messageId }
            if (target == null) {
                _notice.value = context.getString(R.string.chat_rewind_no_checkpoint)
                return@launch
            }
            runCatching {
                val plan = harnessLoop.prepareRewind(sessionId, target.turn, scope)
                val result = harnessLoop.commitRewind(plan, workspace.value)
                val forkedSessionId = result.forkedSessionId
                if (forkedSessionId != null) {
                    harnessLoop.loadSession(forkedSessionId)
                }
                _notice.value = buildString {
                    append(context.getString(R.string.chat_rewind_done, result.filesRestored, result.filesDeleted))
                    if (forkedSessionId != null) append(" · ").append(context.getString(R.string.chat_rewind_switched))
                    result.note?.let { append("\n").append(it) }
                }
            }.onFailure { throwable ->
                _notice.value = context.getString(R.string.chat_rewind_failed, throwable.message ?: "unknown")
            }
        }
    }

    // ---- 模型管理 ----

    private val _providerModelIds = MutableStateFlow<List<String>>(emptyList())
    val providerModelIds: StateFlow<List<String>> = _providerModelIds.asStateFlow()

    private val _discoveringProviderModels = MutableStateFlow(false)
    val discoveringProviderModels: StateFlow<Boolean> = _discoveringProviderModels.asStateFlow()

    private val _providerModelDiscoveryError = MutableStateFlow<String?>(null)
    val providerModelDiscoveryError: StateFlow<String?> = _providerModelDiscoveryError.asStateFlow()

    private val _modelPickerProfileId = MutableStateFlow<String?>(null)
    val modelPickerProfileId: StateFlow<String?> = _modelPickerProfileId.asStateFlow()

    fun addModel(name: String, provider: String, model: String, baseUrl: String) {
        val trimmedName = name.trim().ifBlank { model }
        val trimmedModel = model.trim()
        if (trimmedModel.isBlank()) return
        viewModelScope.launch {
            val id = "${provider.trim().lowercase()}-${trimmedModel.lowercase()}"
                .replace(Regex("[^a-z0-9-]"), "-")
            // 派生 id 与既有档案冲突时提示而非静默覆盖
            if (aiModelDao.findById(id) != null) {
                _notice.value = context.getString(R.string.chat_model_profile_exists)
                return@launch
            }
            profileWriter.upsertProfile(
                AiProfileWriter.UpsertRequest(
                    id = id,
                    name = trimmedName,
                    provider = provider.trim().ifBlank { trimmedModel },
                    model = trimmedModel,
                    baseUrl = baseUrl.trim(),
                ),
            )
        }
    }

    fun setActiveModel(id: String) {
        selectModel(id)
    }

    private val _subagentResult = MutableStateFlow<SubagentResultUiState?>(null)
    val subagentResult: StateFlow<SubagentResultUiState?> = _subagentResult.asStateFlow()

    fun openSubagentResult(branch: ConversationBranch) {
        if (branch.kind != ConversationBranchKind.SUBAGENT || branch.laneName.isNullOrBlank()) return
        val sessionId = currentSessionId.value.takeIf { it.isNotBlank() } ?: return
        _subagentResult.value = SubagentResultUiState(sessionId = sessionId, branch = branch)
        loadSubagentResult(sessionId, branch)
    }

    fun refreshSubagentResult() {
        val current = _subagentResult.value ?: return
        _subagentResult.value = current.copy(loading = true, error = null)
        loadSubagentResult(current.sessionId, current.branch)
    }

    fun closeSubagentResult() {
        _subagentResult.value = null
    }

    private fun loadSubagentResult(sessionId: String, branch: ConversationBranch) {
        val laneName = branch.laneName ?: return
        viewModelScope.launch {
            runCatching {
                val latestBranch = laneManager.branches(sessionId)
                    .firstOrNull { it.kind == ConversationBranchKind.SUBAGENT && it.laneName == laneName }
                    ?: branch
                latestBranch to laneManager.subagentTranscript(sessionId, laneName)
            }.onSuccess { (latestBranch, transcript) ->
                val current = _subagentResult.value
                if (current?.sessionId == sessionId && current.branch.laneName == laneName) {
                    _subagentResult.value = current.copy(
                        branch = latestBranch,
                        messages = transcript,
                        loading = false,
                        error = null,
                    )
                }
            }.onFailure { throwable ->
                val current = _subagentResult.value
                if (current?.sessionId == sessionId && current.branch.laneName == laneName) {
                    _subagentResult.value = current.copy(
                        loading = false,
                        error = throwable.message ?: "无法读取子智能体成果",
                    )
                }
            }
        }
    }

    fun selectModel(id: String, subModel: String? = null) {
        viewModelScope.launch {
            val entity = aiModelDao.findById(id) ?: return@launch
            val sessionId = currentSessionId.value.takeIf { it.isNotBlank() } ?: return@launch
            val variant = subModel?.trim()?.takeIf { it.isNotBlank() }
                ?: entity.model.substringBefore(',').trim().takeIf { it.isNotBlank() }
            sessionDao.setModelSelection(sessionId, entity.id, variant, System.currentTimeMillis())
        }
    }

    /** 打开某供应商档案后，通过 v1/models 拉取同端点可用模型列表。 */
    fun openProviderModelPicker(profileId: String) {
        _modelPickerProfileId.value = profileId
        discoverProviderModels(profileId)
    }

    fun closeProviderModelPicker() {
        _modelPickerProfileId.value = null
        _providerModelIds.value = emptyList()
        _providerModelDiscoveryError.value = null
        _discoveringProviderModels.value = false
    }

    fun discoverProviderModels(profileId: String) {
        viewModelScope.launch {
            val profile = aiModelDao.findById(profileId) ?: return@launch
            _discoveringProviderModels.value = true
            _providerModelDiscoveryError.value = null
            _providerModelIds.value = emptyList()
            val provider = providerCatalog.find(profile.provider)
            val baseUrl = profile.baseUrl.ifBlank { provider.baseUrl }
            val cleanUrl = ProviderEndpointPolicy.normalizeUrl(baseUrl)
            if (!ProviderEndpointPolicy.isSafeBaseUrl(cleanUrl)) {
                _providerModelDiscoveryError.value = context.getString(R.string.chat_model_discovery_bad_url)
                _discoveringProviderModels.value = false
                return@launch
            }
            val apiKey = profile.secretRef.takeIf { it.isNotBlank() }
                ?.let { providerRepository.readModelApiKeys(it).firstOrNull() }
                ?: providerRepository.readApiKey()
            runCatching { modelDiscovery.discover(provider, cleanUrl, apiKey) }
                .onSuccess { ids ->
                    _providerModelIds.value = ids
                    if (ids.isEmpty()) {
                        _providerModelDiscoveryError.value = context.getString(R.string.chat_model_discovery_empty)
                    }
                }
                .onFailure {
                    Log.w(TAG, "模型发现失败 profile=$profileId", it)
                    _providerModelDiscoveryError.value = context.getString(R.string.chat_model_discovery_failed)
                }
            _discoveringProviderModels.value = false
        }
    }

    /**
     * 在同一供应商档案内为当前会话选择具体模型 ID（复用 baseUrl / Key / 推理参数）。
     * 档案本身保持不变，避免其他会话被连带切换。
     */
    fun switchModelInProfile(profileId: String, modelId: String) {
        val trimmed = modelId.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val profile = aiModelDao.findById(profileId) ?: return@launch
            val sessionId = currentSessionId.value.takeIf { it.isNotBlank() } ?: return@launch
            sessionDao.setModelSelection(sessionId, profile.id, trimmed, System.currentTimeMillis())
            closeProviderModelPicker()
        }
    }

    fun updateActiveModelReasoning(mode: String?, effort: String?) {
        viewModelScope.launch {
            val sessionModelId = currentSessionId.value.takeIf { it.isNotBlank() }
                ?.let { sessionDao.findById(it)?.modelId }
            val profile = sessionModelId?.let { aiModelDao.findById(it) } ?: aiModelDao.activeModel() ?: return@launch
            aiModelDao.updateReasoning(profile.id, mode, effort)
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            profileWriter.deleteProfile(id)
        }
    }
}

internal fun mergeHistoricalAndLiveEvents(
    sessionId: String,
    messages: List<HarnessMessage>,
    live: List<HarnessEvent>,
): List<HarnessEvent> {
    if (sessionId.isBlank()) return emptyList()
    if (live.isEmpty()) return synthesizeHistoricalEvents(sessionId, messages)
    if (messages.isEmpty()) return live

    val liveEntryIds = mutableSetOf<String>()
    live.forEach { event ->
        when (event) {
            is HarnessEvent.ProviderRoundSettled -> event.entryId?.let { liveEntryIds.add(it) }
            is HarnessEvent.ToolCallStarted -> liveEntryIds.add(event.toolCallId)
            is HarnessEvent.ToolCallSettled -> liveEntryIds.add(event.toolCallId)
            else -> Unit
        }
    }

    val liveMinTimestamp = live.minOfOrNull { it.timestamp } ?: Long.MAX_VALUE
    val priorMessages = messages.filter { msg ->
        msg.createdAt < liveMinTimestamp && !liveEntryIds.contains(msg.id)
    }

    if (priorMessages.isEmpty()) return live

    val priorEvents = synthesizeHistoricalEvents(sessionId, priorMessages)
    return priorEvents + live
}

private fun synthesizeHistoricalEvents(sessionId: String, messages: List<HarnessMessage>): List<HarnessEvent> {
    if (sessionId.isBlank() || messages.isEmpty()) return emptyList()
    val events = mutableListOf<HarnessEvent>()
    var currentRound = 0
    var opId = "hist-$sessionId"
    val toolCallMap = messages.filterIsInstance<ToolCall>().associateBy { it.id }

    messages.forEach { msg ->
        when (msg) {
            is UserMessage -> {
                currentRound++
                opId = "hist-${msg.id}"
                events.add(
                    HarnessEvent.OperationStarted(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        laneName = "main",
                    )
                )
            }
            is AssistantText -> {
                events.add(
                    HarnessEvent.ProviderRoundStarted(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        round = currentRound,
                        attempt = 1,
                        modelId = null,
                    )
                )
                events.add(
                    HarnessEvent.ProviderRoundSettled(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        round = currentRound,
                        entryId = msg.id,
                        inputTokens = 0L,
                        outputTokens = msg.text.length.toLong(),
                    )
                )
            }
            is ToolCall -> {
                events.add(
                    HarnessEvent.ToolCallStarted(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        toolCallId = msg.id,
                        toolName = msg.tool.name.lowercase(),
                    )
                )
            }
            is ToolResult -> {
                val call = toolCallMap[msg.toolCallId]
                val toolName = call?.tool?.name?.lowercase() ?: "tool"
                events.add(
                    HarnessEvent.ToolCallSettled(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        toolCallId = msg.toolCallId,
                        toolName = toolName,
                        success = msg.success,
                        durationMs = msg.durationMs,
                    )
                )
            }
            else -> Unit
        }
    }
    return events
}

enum class ComposerSendMode(val queue: PromptQueue) {
    STEER(PromptQueue.STEER),
    NEXT_RUN(PromptQueue.NEXT_RUN),
}

private data class ContextUsageInputs(
    val currentMessages: List<HarnessMessage>,
    val activeModel: AiModelEntity?,
    val skills: List<top.wkbin.taixu.core.model.AgentSkill>,
    val mcps: List<top.wkbin.taixu.core.model.McpServerConfig>,
    val defaultBudget: Int,
)

data class ContextUsage(
    val usedTokens: Int = 0,
    val limitTokens: Int = 128_000,
    val systemTokens: Int = 0,
    val toolTokens: Int = 0,
    val conversationTokens: Int = 0,
    val compacted: Boolean = false,
    val cachedTokens: Long = 0L,
    val cacheHitRatePercent: Int? = null,
    val breakdown: ContextUsageBreakdown = ContextUsageBreakdown(),
)


data class MentionItem(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val type: MentionType,
    val icon: top.wkbin.taixu.ui.components.RuntimeIconName,
)

enum class MentionType {
    SKILL, MCP_SERVER
}
