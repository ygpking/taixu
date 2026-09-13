package top.wkbin.taixu.ui.chat.git

import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import top.wkbin.taixu.core.datastore.GitCredential
import top.wkbin.taixu.core.datastore.GitPreferences
import top.wkbin.taixu.harness.ApiMessage
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.ShellCommand
import top.wkbin.taixu.ui.chat.GitAiCommitState
import top.wkbin.taixu.ui.chat.GitAuth
import top.wkbin.taixu.ui.chat.GitCredHealth
import top.wkbin.taixu.ui.chat.GitFileChange
import top.wkbin.taixu.ui.chat.GitOpAction
import top.wkbin.taixu.ui.chat.GitOpMessage
import top.wkbin.taixu.ui.chat.GitPanelState
import top.wkbin.taixu.ui.chat.GitProgress
import top.wkbin.taixu.ui.chat.GitRepoInfo
import top.wkbin.taixu.ui.chat.GitRepoListState
import top.wkbin.taixu.ui.chat.shellQuote

/**
 * Git 可视化工作台控制器（第 3 项搬运）。
 *
 * 来源：万象 Wanxiang `feature/chat/.../ChatViewModel.kt` L323~L1331（约 1009 行）内嵌 git 子系统，
 * 2026-09-13 抽取为独立控制器并适配本 fork。**为何不整段塞回 ChatViewModel**：Wanxiang 的 git 逻辑
 * 直接依赖 4 个 fork 不具备的成员（providerClient / fullSettingsStore / harnessLoop.debugSetWorkspace
 * / GitPreferences），整段搬必断引用；抽成 controller 后核心 ViewModel 只增 1 个字段 + 1 个注入。
 *
 * 能力：仓库状态刷新、文件 diff、stage/unstage/commit、pull/push/stash/分支/tag、clone（含流式进度与
 * 网络重试）、凭证 CRUD 与健康检查、远端仓库列表、AI 生成 commit message。
 */
class GitPanelController(
    private val linuxRuntime: LinuxRuntime,
    private val gitPreferences: GitPreferences,
    private val providerClient: ProviderClient,
    private val scope: CoroutineScope,
    /** 当前工作区（会话绑定）Linux 路径；空串表示未关联。 */
    private val workspaceProvider: () -> String,
    /** 把工作区切到给定相对路径（由 ChatViewModel 委托 HarnessLoop 完成）。 */
    private val onSwitchWorkspace: (String) -> Unit,
) {

    // ===== Git 面板状态（绑定当前会话，不跨会话共享）=====
    private val _gitPanelState = MutableStateFlow(GitPanelState())
    val gitPanelState: StateFlow<GitPanelState> = _gitPanelState.asStateFlow()

    /** 读取当前会话工作区的 Git 状态（分支 + 改动 + 分支列表 + 提交历史）。 */
    fun refreshGitStatus() {
        // 会话未绑定工作区时回退到沙箱内 /workspace 根目录；相对路径补 /workspace 前缀
        val ws = workspaceProvider().ifBlank { "/workspace" }
            .let { if (it.startsWith("/")) it else "/workspace/$it" }
        _gitPanelState.value = GitPanelState(loading = true)
        scope.launch(Dispatchers.IO) {
            val statusOut = runGitRead(ws, "git status --porcelain -b -uall")
            if (statusOut == null || statusOut.contains("not a git repository", ignoreCase = true)) {
                _gitPanelState.value = GitPanelState(loading = false, notARepo = true)
                return@launch
            }
            val lines = statusOut.lines().filter { it.isNotBlank() }
            val branchHeader = lines.firstOrNull { it.startsWith("## ") }?.removePrefix("## ") ?: ""
            val branch = branchHeader
                .substringBefore("...")
                .substringBefore(" [")
                .trim()
                .removePrefix("No commits yet on ")
                .takeIf { it.isNotBlank() }
            // 解析形如 `[ahead 2, behind 5]` 或 `[ahead 2]` / `[behind 3]`
            val aheadBehind = Regex("""\[([^\]]+)\]""").find(branchHeader)?.groupValues?.get(1)?.let { inner ->
                val ahead = Regex("ahead (\\d+)").find(inner)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val behind = Regex("behind (\\d+)").find(inner)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (ahead == 0 && behind == 0) null else ahead to behind
            }
            val staged = mutableListOf<GitFileChange>()
            val unstaged = mutableListOf<GitFileChange>()
            val untracked = mutableListOf<String>()
            for (line in lines.filterNot { it.startsWith("## ") }) {
                if (line.length < 4) continue
                val x = line[0]
                val y = line[1]
                val path = line.drop(3)
                when {
                    x == '?' && y == '?' -> untracked += path
                    x != ' ' -> staged += GitFileChange(x, path)
                    y != ' ' -> unstaged += GitFileChange(y, path)
                }
            }
            // 注意：%(refname:short) 的括号必须引号包裹——裸括号会被 dash 当语法错误，
            // 整个命令 exit 2 → 分支列表恒空（E2E 实锤）。
            val localBranches = runGitRead(ws, "git branch --format='%(refname:short)'")
                ?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val remoteBranches = runGitRead(ws, "git branch -r --format='%(refname:short)'")
                ?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val tags = runGitRead(ws, "git tag --list")
                ?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val graph = buildFreshGraph(ws)
            val hasIdentity = runGitRead(ws, "git config user.name")?.isNotBlank() == true &&
                runGitRead(ws, "git config user.email")?.isNotBlank() == true
            val hasRemote = runGitRead(ws, "git remote")?.isNotBlank() == true
            val isShallow = runGitRead(ws, "git rev-parse --is-shallow-repository")?.trim() == "true"
            val stashCount = runGitRead(ws, "git stash list")
                ?.lines()?.count { it.isNotBlank() } ?: 0
            _gitPanelState.value = _gitPanelState.value.copy(
                loading = false,
                branch = branch,
                aheadBehind = aheadBehind,
                stashCount = stashCount,
                staged = staged,
                unstaged = unstaged,
                untracked = untracked,
                localBranches = localBranches,
                remoteBranches = remoteBranches,
                tags = tags,
                graph = graph,
                commitFiles = emptyMap(),
                hasIdentity = hasIdentity,
                hasRemote = hasRemote,
                isShallow = isShallow,
                // 与原"新建 GitPanelState"语义一致：刷新清一次性视图态
                diffPath = null,
                diffText = null,
                diffLoading = false,
                commitDetailHash = null,
                commitDetailText = null,
                commitDetailLoading = false,
                loadingCommit = null,
                graphLoadingMore = false,
                pullDirtyConfirm = false,
                pendingCheckout = null,
                error = null,
            )
        }
    }

    /** 分页游标：已加载的图提交（新→旧）与全量 refs 映射（refresh 时重置）。 */
    private var loadedGraphCommits: List<GraphCommit> = emptyList()
    private var graphRefsByCommit: Map<String, List<GitGraphRef>> = emptyMap()

    /** 解析 `git for-each-ref` → hash→refs 映射（本地/远程分支 + 标签，一次命令拿全）。 */
    private suspend fun loadGraphRefs(ws: String): Map<String, List<GitGraphRef>> {
        val raw = runGitRead(
            ws,
            "git for-each-ref --format=\"%(refname)%09%(objectname)%09%(*objectname)%09%(HEAD)\" refs/heads refs/remotes refs/tags",
        ) ?: return emptyMap()
        val map = mutableMapOf<String, MutableList<GitGraphRef>>()
        for (line in raw.lines()) {
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val full = parts[0]
            // 附注标签的 objectname 指向 tag 对象，peeled 的 *objectname 才是提交哈希。
            val hash = parts[2].ifBlank { parts[1] }.trim()
            if (hash.length < 7) continue
            val short = full.removePrefix("refs/heads/").removePrefix("refs/remotes/").removePrefix("refs/tags/")
            val isRemote = full.startsWith("refs/remotes/")
            val isBranch = full.startsWith("refs/heads/") || isRemote
            val isCurrent = parts.size > 3 && parts[3] == "*" && full.startsWith("refs/heads/")
            map.getOrPut(hash) { mutableListOf() } += GitGraphRef(
                name = short, isBranch = isBranch, isCurrent = isCurrent, isRemote = isRemote,
            )
        }
        return map
    }

    /** 重拉第一页（30 条）并组装拓扑图；分页游标重置。 */
    /** 反浅克隆：拉取完整历史后重建提交图（仅浅克隆仓库在图页露出该入口）。 */
    fun unshallowRepo() {
        if (_gitProgress.value != null) return
        runStreamingGitOp(
            label = "加载完整历史",
            cmd = "git fetch --unshallow origin --progress",
            resolveHostFromOrigin = true,
            timeoutMs = 900_000L,
        )
    }

    private suspend fun buildFreshGraph(ws: String): GitGraph {
        val logRaw = runGitRead(ws, "git log --pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%P%x1f%b%x1f%s%x1e -30") ?: ""
        loadedGraphCommits = GitGraphBuilder.parseGraphCommits(logRaw)
        graphRefsByCommit = loadGraphRefs(ws)
        Log.i(
            "GitGraph",
            "fresh ws=$ws rawLen=${logRaw.length} hasSep=${logRaw.contains('\u001f')} commits=${loadedGraphCommits.size} refs=${graphRefsByCommit.size} " +
                "head=${logRaw.take(40)}",
        )
        return GitGraphBuilder.buildGraph(
            loadedGraphCommits, graphRefsByCommit, hasMore = loadedGraphCommits.size >= 30,
        )
    }

    /** 上拉加载更早提交（--skip 分页，AiCode graphAppend 语义）。 */
    fun loadMoreCommits() {
        val st = _gitPanelState.value
        if (st.graphLoadingMore || !st.graph.hasMore) return
        _gitPanelState.value = st.copy(graphLoadingMore = true)
        scope.launch(Dispatchers.IO) {
            val ws = currentGitWs()
            val skip = loadedGraphCommits.size
            val raw = runGitRead(
                ws,
                "git log --skip=$skip --pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%P%x1f%b%x1f%s%x1e -30",
            ) ?: ""
            val more = GitGraphBuilder.parseGraphCommits(raw)
            val seen = loadedGraphCommits.mapTo(HashSet()) { it.hash }
            loadedGraphCommits = loadedGraphCommits + more.filter { it.hash !in seen }
            val graph = GitGraphBuilder.buildGraph(
                loadedGraphCommits, graphRefsByCommit, hasMore = more.size >= 30,
            )
            _gitPanelState.value = _gitPanelState.value.copy(graph = graph, graphLoadingMore = false)
        }
    }

    private suspend fun runGitRead(ws: String, cmd: String): String? {
        val result = linuxRuntime.execute(
            ShellCommand(
                commandLine = "$cmd 2>&1 || true",
                workingDirectory = ws,
                timeoutMs = 15_000L,
            ),
        )
        return if (result.isSuccess) {
            (result.stdout + "\n" + result.stderr).trim().takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    /** 查看某个改动文件的 diff（相对 HEAD，含暂存与未暂存）。 */
    fun loadFileDiff(path: String) {
        val ws = workspaceProvider().ifBlank { "/workspace" }.let { if (it.startsWith("/")) it else "/workspace/$it" }
        _gitPanelState.value = _gitPanelState.value.copy(diffPath = path, diffText = null, diffLoading = true)
        scope.launch(Dispatchers.IO) {
            val quoted = "'${path.replace("'", "'\\''")}'"
            val diff = runGitRead(ws, "git diff HEAD -- $quoted")
            _gitPanelState.value = _gitPanelState.value.copy(
                diffPath = path,
                diffText = diff ?: "未跟踪的新文件（无 diff）",
                diffLoading = false,
            )
        }
    }

    /** 关闭 diff 视图，返回状态列表。 */
    fun clearDiff() {
        _gitPanelState.value = _gitPanelState.value.copy(diffPath = null, diffText = null, diffLoading = false)
    }

    fun gitStage(path: String) = runGitWrite("git add -- ${shellQuote(path)}")
    fun gitUnstage(path: String) = runGitWrite("git reset HEAD -- ${shellQuote(path)}")
    fun gitStageAll() = runGitWrite("git add -A")
    fun gitUnstageAll() = runGitWrite("git reset HEAD")
    fun gitCommit(message: String) = runGitWrite("git commit -m ${shellQuote(message)}")
    /**
     * 本地有未提交改动时先弹确认（可能覆盖或冲突）；干净时直接 pull。
     * 通过 [gitPanelState] 的 `pullDirtyConfirm` 字段驱动 UI 弹窗，用户在 UI 上点"继续拉取"再调 [gitPullNow]。
     */
    fun gitPull() {
        val s = _gitPanelState.value
        val dirty = s.staged.isNotEmpty() || s.unstaged.isNotEmpty()
        if (dirty) {
            _gitPanelState.value = s.copy(pullDirtyConfirm = true)
        } else {
            gitPullNow()
        }
    }

    fun gitPullNow() {
        _gitPanelState.value = _gitPanelState.value.copy(pullDirtyConfirm = false)
        runStreamingGitOp(label = "拉取", cmd = "git pull --progress", resolveHostFromOrigin = true, timeoutMs = 300_000L)
    }

    fun dismissPullDirtyConfirm() {
        _gitPanelState.value = _gitPanelState.value.copy(pullDirtyConfirm = false)
    }
    fun gitPush() {
        scope.launch(Dispatchers.IO) {
            val ws = currentGitWs()
            // 检查有无 upstream；若没 → 首次 push 用 `git push -u origin <branch>` 自动建立
            val branch = _gitPanelState.value.branch ?: runGitRead(ws, "git rev-parse --abbrev-ref HEAD")?.trim()
            val upstreamSet = runGitRead(ws, "git rev-parse --abbrev-ref --symbolic-full-name @{u}")
            val hasUpstream = upstreamSet != null && !upstreamSet.contains("unknown") && !upstreamSet.contains("no upstream")
            val cmd = if (hasUpstream) "git push --progress"
                else "git push --progress -u origin ${shellQuote(branch ?: "HEAD")}"
            runStreamingGitOp(label = "推送", cmd = cmd, resolveHostFromOrigin = true, timeoutMs = 300_000L, syncTrackingRef = true)
        }
    }
    /** 一键 stash 当前所有改动（含未跟踪），完成后 Snackbar 带 [还原 stash] 一键 pop。 */
    fun gitStash(message: String = "") {
        runGitWriteWithSuccess(
            cmd = "git stash push -u${if (message.isNotBlank()) " -m " + shellQuote(message) else ""}",
            successMessage = "✓ 已 stash（改动被暂存，工作区已干净）",
            successAction = GitOpAction.StashPop,
        )
    }
    /** 弹出最近一个 stash（保留记录用 apply；彻底用 pop）。 */
    fun gitStashPop() = runGitWrite("git stash pop")
    fun gitStashApply() = runGitWrite("git stash apply")
    fun gitStashDrop(index: Int = 0) = runGitWrite("git stash drop stash@{$index}")
    /**
     * 切分支前检查工作区是否脏（P1-12）：
     * - 若 dirty + 无 `checkoutDirtyConfirm` → 弹二次确认（可能覆盖或冲突）
     * - 干净或已确认 → 直接 checkout
     */
    fun gitCheckout(branch: String) {
        val s = _gitPanelState.value
        val dirty = s.staged.isNotEmpty() || s.unstaged.isNotEmpty()
        if (dirty && s.pendingCheckout != branch) {
            _gitPanelState.value = s.copy(pendingCheckout = branch)
            return
        }
        _gitPanelState.value = _gitPanelState.value.copy(pendingCheckout = null)
        runGitWrite("git checkout ${shellQuote(branch)}")
    }

    fun confirmCheckoutDirty(branch: String) {
        _gitPanelState.value = _gitPanelState.value.copy(pendingCheckout = null)
        runGitWrite("git checkout ${shellQuote(branch)}")
    }

    fun dismissCheckoutConfirm() {
        _gitPanelState.value = _gitPanelState.value.copy(pendingCheckout = null)
    }
    fun gitCreateBranch(name: String) = runGitWrite("git checkout -b ${shellQuote(name)}")
    fun gitDeleteBranch(branch: String) = runGitWrite("git branch -d ${shellQuote(branch)}")
    fun gitInit() = runGitWrite("git init")
    /**
     * 克隆到当前工作区下的一个以仓库名命名的子目录。**流式进度**通过 [gitProgress] StateFlow 上抛，
     * 顶栏横幅实时显示；成功后 [gitOpMessage] 带 [GitOpAction.SwitchWorkspaceTo] 一键切工作区。
     * 失败按错误种类映射人话文案（同名目录 / 网络 / 认证 / URL 无效等），网络类自动重试 2 次。
     */
    fun gitClone(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            _gitOpMessage.value = GitOpMessage.Error("仓库 URL 是空的，先粘贴一个再点克隆")
            return
        }
        scope.launch(Dispatchers.IO) { gitPreferences.pushRecentCloneUrl(trimmed) }
        val repoName = trimmed.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
        val ws = currentGitWs()
        scope.launch(Dispatchers.IO) {
            // 工作区不存在 → 自动 mkdir -p 重建（clone 本质上是创建项目动作，父目录不该要求预先存在）。
            // 若 mkdir 也失败（比如 /workspace 挂载点异常），才给用户报错。
            if (!workspaceExists(ws)) {
                val mkdirRes = runCatching {
                    linuxRuntime.execute(
                        ShellCommand(
                            commandLine = "mkdir -p ${shellQuote(ws)} 2>&1 && test -d ${shellQuote(ws)} && echo ok",
                            workingDirectory = "/root",
                            timeoutMs = 15_000L,
                        ),
                    )
                }.getOrNull()
                val created = mkdirRes?.stdout?.trim()?.endsWith("ok") == true
                if (!created) {
                    _gitOpMessage.value = GitOpMessage.Error(
                        "工作区 `$ws` 不存在且自动创建失败。可能是 /workspace 挂载异常，试试重启 App 或到工坊页检查",
                    )
                    return@launch
                }
                Log.i("GitClone", "工作区不存在，已自动 mkdir -p $ws")
            }
            // 目标已存在 → 明确提示，给"清空再试"选项
            if (workspaceExists("$ws/$repoName")) {
                _gitOpMessage.value = GitOpMessage.Error(
                    "`$repoName/` 已存在。要不要清空再重新克隆？（会删除现有内容）",
                    action = GitOpAction.RetryWithClean(trimmed, "$ws/$repoName"),
                )
                return@launch
            }
            // 网络类自动重试：2 次退避（1s → 3s）
            var attempt = 0
            var lastError = ""
            while (attempt < 3 && !cancelRequested.get()) {
                if (attempt > 0) {
                    _gitProgress.value = GitProgress(label = "重试第 $attempt 次…")
                    delay(1_000L * attempt)
                }
                cancelRequested.set(false)
                _gitProgress.value = GitProgress(label = "开始克隆 $repoName（第 ${attempt + 1} 次）…")
                val outcome = doCloneOnce(trimmed, repoName, ws)
                when (outcome) {
                    is CloneOutcome.Success -> {
                        _gitProgress.value = null
                        _gitOpMessage.value = GitOpMessage.Ok(
                            message = "✓ 已克隆到 $repoName/",
                            action = GitOpAction.SwitchWorkspaceTo("$ws/$repoName"),
                        )
                        refreshGitStatus()
                        return@launch
                    }
                    is CloneOutcome.Cancelled -> {
                        _gitProgress.value = null
                        _gitOpMessage.value = GitOpMessage.Error("已取消")
                        return@launch
                    }
                    is CloneOutcome.Failure -> {
                        lastError = outcome.rawOutput
                        // 网络/临时错误才重试；其它直接失败
                        if (!isRetryable(outcome.rawOutput)) {
                            _gitProgress.value = null
                            val friendly = translateGitError(outcome.rawOutput, repoName)
                            _gitOpMessage.value = GitOpMessage.Error(friendly, action = actionForError(friendly, trimmed, "$ws/$repoName"))
                            return@launch
                        }
                        attempt++
                    }
                }
            }
            _gitProgress.value = null
            _gitOpMessage.value = GitOpMessage.Error(
                "克隆失败（重试 3 次仍不通）：\n" + translateGitError(lastError, repoName),
                action = GitOpAction.RetrySame("clone:$trimmed"),
            )
        }
    }

    private sealed interface CloneOutcome {
        data object Success : CloneOutcome
        data class Failure(val rawOutput: String) : CloneOutcome
        data object Cancelled : CloneOutcome
    }

    private suspend fun doCloneOnce(url: String, repoName: String, ws: String): CloneOutcome {
        val host = GitAuth.hostOf(url)
        val creds = gitPreferences.credentials.first()
        val cred = GitAuth.findCredential(creds, host)
        val raw = "git clone --progress --depth 1 ${shellQuote(url)} ${shellQuote(repoName)}"
        val effective = if (cred != null) GitAuth.wrap(raw, cred) else raw
        // 用 forcePty 让 git 走 tty，能吐 `% Receiving objects: NN%` 进度
        val result = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "$effective 2>&1",
                    workingDirectory = ws,
                    timeoutMs = 600_000L,
                    onOutput = { chunk -> applyGitProgress(chunk, "克隆 $repoName…") },
                    forcePty = true,
                ),
            )
        }
        val r = result.getOrNull()
        val out = ((r?.stdout ?: "") + "\n" + (r?.stderr ?: "")).trim()
        return when {
            cancelRequested.get() -> CloneOutcome.Cancelled
            r != null && r.isSuccess -> CloneOutcome.Success
            else -> CloneOutcome.Failure(out.ifBlank { "git 无输出（可能网络不通或 URL 错误）" })
        }
    }

    private suspend fun workspaceExists(path: String): Boolean {
        val r = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "test -d ${shellQuote(path)} && echo yes || echo no",
                    workingDirectory = "/root",
                    timeoutMs = 10_000L,
                ),
            )
        }.getOrNull()
        return r?.stdout?.trim() == "yes"
    }

    private fun isRetryable(out: String): Boolean {
        val l = out.lowercase()
        return "failed to connect" in l || "could not resolve host" in l ||
            "connection reset" in l || "timed out" in l || "rpc failed" in l ||
            "early eof" in l || "the requested url returned error: 5" in l
    }

    /** 把 git stderr 翻成人话（保留原摘要供调试）。 */
    private fun translateGitError(out: String, repoName: String = ""): String {
        val l = out.lowercase()
        val core = when {
            "already exists and is not an empty directory" in l ->
                "`$repoName` 目录已存在且非空。可清空再试或改目录名。"
            "authentication failed" in l || "invalid credentials" in l || "password authentication" in l ->
                "认证失败：用户名或 PAT 不对/过期。到「凭证」页更新。"
            "could not read username" in l || "terminal prompts disabled" in l ->
                "私有仓库需要凭证。请先到「凭证」页添加该主机的 PAT。"
            "permission denied" in l ->
                "权限不足：账号没这个仓库的读写权，或 PAT 缺 scope。"
            "repository not found" in l || "not found" in l && "http" in l ->
                "仓库不存在（404）：URL 拼错了，或它是私有的但你无权访问。"
            "unable to access" in l || "failed to connect" in l || "could not resolve host" in l ->
                "网络不通：手机没连上代理，或 GitHub/Gitee 不可达。"
            "connection reset" in l || "timed out" in l ->
                "网络被重置：可能中途断流。稍后再试。"
            "no space left on device" in l ->
                "手机存储不够。清理工作区或卸载一些项目再试。"
            "empty reply" in l || "rpc failed" in l ->
                "服务端断开：可能仓库太大或对方限流。可以试浅克隆。"
            else -> out.take(400)
        }
        return core
    }

    private fun actionForError(friendly: String, url: String, targetDir: String): GitOpAction? = when {
        "已存在" in friendly -> GitOpAction.RetryWithClean(url, targetDir)
        "网络不通" in friendly || "网络被重置" in friendly || "服务端断开" in friendly ->
            GitOpAction.RetrySame("clone:$url")
        else -> null
    }

    /** 「同名目录已存在，清空重试」的用户动作。 */
    fun retryCloneAfterClean(url: String, targetDir: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                linuxRuntime.execute(
                    ShellCommand(
                        commandLine = "rm -rf ${shellQuote(targetDir)}",
                        workingDirectory = "/root",
                        timeoutMs = 15_000L,
                    ),
                )
            }
            gitClone(url)
        }
    }
    fun gitConfigIdentity(name: String, email: String) = runGitWrite("git config user.name ${shellQuote(name)} && git config user.email ${shellQuote(email)}")
    fun gitRevert(path: String) = runGitWrite("git checkout -- ${shellQuote(path)}")
    /** 一键回退所有已修改未暂存文件（等价 IDE 里 "Rollback" 未暂存部分）；未跟踪文件不动。 */
    fun gitRevertAllUnstaged() = runGitWrite("git checkout -- .")
    /** 重命名分支：完成后 Snackbar 带 [撤销] 一键换回。 */
    fun gitRenameBranch(oldName: String, newName: String) = runGitWriteWithSuccess(
        cmd = "git branch -m ${shellQuote(oldName)} ${shellQuote(newName)}",
        successMessage = "✓ $oldName 已重命名为 $newName",
        successAction = GitOpAction.UndoRename(oldName = oldName, newName = newName),
    )
    /** 删除远程分支（不可撤销，UI 应二次确认）。 */
    fun gitDeleteRemoteBranch(name: String) = runGitNetworkOp(
        cmd = "git push origin --delete ${shellQuote(name)}",
        resolveHostFromOrigin = true,
        timeoutMs = 120_000L,
    )
    fun gitDeleteUntracked(path: String) = runGitWrite("rm -- ${shellQuote(path)}")
    fun gitCreateTag(name: String) = runGitWrite("git tag ${shellQuote(name)}")
    fun gitDeleteTag(name: String) = runGitWrite("git tag -d ${shellQuote(name)}")
    /** 推单个 tag 到 origin（用网络 op 走 credential + proxy）。 */
    fun gitPushTag(name: String) = runStreamingGitOp(
        label = "推送标签 $name",
        cmd = "git push origin ${shellQuote(name)}",
        resolveHostFromOrigin = true,
        timeoutMs = 180_000L,
    )
    /** 删远端 tag。 */
    fun gitDeleteRemoteTag(name: String) = runStreamingGitOp(
        label = "删远端标签 $name",
        cmd = "git push origin --delete ${shellQuote(name)}",
        resolveHostFromOrigin = true,
        timeoutMs = 180_000L,
    )

    /**
     * 网络型 git 操作（clone/pull/push），带凭证注入。凭证不落 `.git/config`：通过
     * `GIT_CONFIG_KEY_0=credential.helper` + 一次性 `mktemp` 文件传给 git，跑完立即删除。
     * - clone：从入参 URL 解析 host；
     * - pull/push：从当前仓库 `origin` 反查 host。
     */
    /**
     * pull/push 通用流式：设 progress → 用 forcePty 让 git 吐进度 → 解析 → 完成清理。
     * 与 clone 共用 [applyGitProgress] + [translateGitError]。**网络类失败自动 3 次退避重试**。
     */
    private fun runStreamingGitOp(
        label: String,
        cmd: String,
        resolveHostFromOrigin: Boolean,
        timeoutMs: Long,
        syncTrackingRef: Boolean = false,
    ) {
        val ws = currentGitWs()
        scope.launch(Dispatchers.IO) {
            cancelRequested.set(false)
            val host = if (resolveHostFromOrigin) {
                GitAuth.hostOf(runGitRead(ws, "git remote get-url origin").orEmpty().trim())
            } else null
            val creds = gitPreferences.credentials.first()
            val cred = GitAuth.findCredential(creds, host)
            val effective = if (cred != null) GitAuth.wrap(cmd, cred) else cmd
            var attempt = 0
            var lastOut = ""
            var lastExit: Int? = null
            while (attempt < 3 && !cancelRequested.get()) {
                if (attempt > 0) {
                    _gitProgress.value = GitProgress(label = "$label 重试第 $attempt 次…")
                    delay(1_000L * attempt)
                } else {
                    _gitProgress.value = GitProgress(label = "$label 中…")
                }
                val result = runCatching {
                    linuxRuntime.execute(
                        ShellCommand(
                            commandLine = "$effective 2>&1",
                            workingDirectory = ws,
                            timeoutMs = timeoutMs,
                            onOutput = { chunk -> applyGitProgress(chunk, "$label 中…") },
                            forcePty = true,
                        ),
                    )
                }.getOrNull()
                lastOut = ((result?.stdout ?: "") + "\n" + (result?.stderr ?: "")).trim()
                lastExit = result?.exitCode
                if (result != null && result.isSuccess) {
                    _gitProgress.value = null
                    // 推送成功且走的是临时凭据 URL/助手时，origin 的远程跟踪引用不会自动前进，
                    // 面板会长期误显示「领先 N」。补一次带同凭据的 fetch 同步跟踪引用。
                    if (syncTrackingRef) {
                        val br = _gitPanelState.value.branch
                            ?: runGitRead(ws, "git rev-parse --abbrev-ref HEAD")?.trim()
                        if (!br.isNullOrBlank() && br != "HEAD") {
                            val fetchCmd = "git fetch --quiet origin +refs/heads/${shellQuote(br)}:refs/remotes/origin/${shellQuote(br)}"
                            runCatching {
                                linuxRuntime.execute(
                                    ShellCommand(
                                        commandLine = "${if (cred != null) GitAuth.wrap(fetchCmd, cred) else fetchCmd} 2>&1",
                                        workingDirectory = ws,
                                        timeoutMs = 120_000L,
                                    ),
                                )
                            }
                        }
                    }
                    _gitOpMessage.value = GitOpMessage.Ok("✓ $label 完成")
                    refreshGitStatus()
                    return@launch
                }
                if (!isRetryable(lastOut)) break
                attempt++
            }
            _gitProgress.value = null
            _gitOpMessage.value = GitOpMessage.Error(
                message = "$label 失败：\n" + translateGitError(lastOut),
                action = if (isRetryable(lastOut)) GitOpAction.RetrySame(cmd) else null,
            )
            refreshGitStatus()
        }
    }

    private fun runGitNetworkOp(
        cmd: String,
        resolveHostFromOrigin: Boolean,
        explicitHost: String? = null,
        timeoutMs: Long,
    ) {
        val ws = currentGitWs()
        val isPush = cmd.startsWith("git push")
        val isPull = cmd.startsWith("git pull")
        _gitOpMessage.value = GitOpMessage.Busy(if (isPush) "正在推送…" else if (isPull) "正在拉取…" else "正在执行 git 命令…")
        scope.launch(Dispatchers.IO) {
            val host = if (resolveHostFromOrigin) {
                GitAuth.hostOf(runGitRead(ws, "git remote get-url origin").orEmpty().trim())
            } else {
                explicitHost
            }
            val creds = gitPreferences.credentials.first()
            val cred = GitAuth.findCredential(creds, host)
            val effective = if (cred != null) GitAuth.wrap(cmd, cred) else cmd
            val result = runCatching {
                linuxRuntime.execute(
                    ShellCommand(
                        commandLine = "$effective 2>&1",
                        workingDirectory = ws,
                        timeoutMs = timeoutMs,
                    ),
                )
            }
            val r = result.getOrNull()
            val out = ((r?.stdout ?: "") + "\n" + (r?.stderr ?: "")).trim()
            val label = if (isPush) "推送" else if (isPull) "拉取" else "操作"
            if (r != null && r.isSuccess) {
                _gitOpMessage.value = GitOpMessage.Ok("✓ $label 成功")
            } else {
                _gitOpMessage.value = GitOpMessage.Error("$label 失败：\n" + out.take(600).ifBlank { "git 无输出" })
            }
            refreshGitStatus()
        }
    }

    // ===== Git 凭证 CRUD（HTTPS 私有仓库：GitHub/Gitee/GitLab PAT 等）=====

    /** 已保存的 Git 凭证列表（加密存储，UI 只显示名称+主机+用户名，token 掩码）。 */
    val gitCredentials: StateFlow<List<GitCredential>> = gitPreferences.credentials
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前工作区 URL 命中的凭证 id（Git 面板顶部提示用），无匹配则 null。 */
    private val _matchedCredentialId = MutableStateFlow<String?>(null)
    val matchedCredentialId: StateFlow<String?> = _matchedCredentialId.asStateFlow()

    /** 最近 5 条克隆 URL（clone 对话框下拉）。 */
    val recentCloneUrls: StateFlow<List<String>> = gitPreferences.recentCloneUrls
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 一次性 git 操作反馈（克隆/推送/拉取）：Busy / Ok / Error。UI 用 Snackbar 显示 + 消费后清回 Idle。 */
    private val _gitOpMessage = MutableStateFlow<GitOpMessage>(GitOpMessage.Idle)
    val gitOpMessage: StateFlow<GitOpMessage> = _gitOpMessage.asStateFlow()
    fun consumeGitOpMessage() { _gitOpMessage.value = GitOpMessage.Idle }

    /** 流式进度：ChatScreen 顶部横幅显示，git 每吐一行进度就更新。null = 无进行中操作。 */
    private val _gitProgress = MutableStateFlow<GitProgress?>(null)
    val gitProgress: StateFlow<GitProgress?> = _gitProgress.asStateFlow()

    /** 用户点横幅右侧 ✕ 取消当前 git 操作（把 [cancelRequested] 置 true，异步任务下次轮询时看到就 kill）。 */
    private val cancelRequested = AtomicBoolean(false)
    fun cancelGitOp() {
        if (_gitProgress.value != null) {
            cancelRequested.set(true)
            _gitProgress.value = _gitProgress.value?.copy(label = "正在取消…")
        }
    }

    /**
     * 从 git 的 stderr 输出里抽进度信息（`Receiving objects: 45% (123/270), 3.5 MiB | 1.2 MiB/s`）。
     * git 用 `\r` 在同一行覆盖写，所以 onOutput 每次的 chunk 可能带 `\r` 分段；只取最后一段。
     */
    private fun applyGitProgress(rawChunk: String, defaultLabel: String) {
        // 取最后一段（覆盖式 progress 输出）
        val last = rawChunk.split('\r').lastOrNull().orEmpty()
        if (last.isBlank()) return
        val phase = when {
            "Counting objects" in last -> "枚举对象"
            "Compressing objects" in last -> "压缩对象"
            "Receiving objects" in last -> "接收对象"
            "Resolving deltas" in last -> "解析增量"
            "Checking out files" in last -> "检出文件"
            "Writing objects" in last -> "写入对象"
            "Enumerating objects" in last -> "枚举对象"
            else -> null
        }
        val percent = Regex("(\\d+)%").find(last)?.groupValues?.get(1)?.toIntOrNull()
        val objFrac = Regex("\\((\\d+)/(\\d+)\\)").find(last)
        val objDone = objFrac?.groupValues?.get(1)?.toIntOrNull()
        val objTotal = objFrac?.groupValues?.get(2)?.toIntOrNull()
        val speedOrBytes = Regex("([\\d.]+\\s*[KM]?B)(?:/s)?").find(last)?.groupValues?.get(1)
        _gitProgress.value = GitProgress(
            label = phase ?: defaultLabel,
            percent = percent,
            objects = if (objDone != null && objTotal != null) "$objDone/$objTotal" else null,
            transfer = speedOrBytes,
            raw = last.take(120),
        )
    }

    fun addGitCredential(name: String, host: String, username: String, token: String) {
        val trimmedHost = host.trim().lowercase().removePrefix("https://").removeSuffix("/")
        if (name.isBlank() || trimmedHost.isBlank() || username.isBlank() || token.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val current = gitPreferences.credentials.first()
            val newId = UUID.randomUUID().toString()
            val updated = current + GitCredential(
                id = newId,
                name = name.trim(),
                host = trimmedHost,
                username = username.trim(),
                token = token.trim(),
                createdAtMillis = System.currentTimeMillis(),
            )
            gitPreferences.setCredentials(updated)
            // 保存后**立即**跑一次健康检查，用户能一眼看到凭证是不是有效（P1-9）
            verifyGitCredential(newId)
        }
    }

    fun deleteGitCredential(id: String) {
        scope.launch(Dispatchers.IO) {
            val current = gitPreferences.credentials.first()
            gitPreferences.setCredentials(current.filterNot { it.id == id })
        }
    }

    /** 用户从 Snackbar 「切过去」按钮触发：把工作区切到某个绝对路径（clone 完成后）。 */
    fun switchWorkspace(path: String) {
        val relative = path.removePrefix("/workspace/").trim('/')
        onSwitchWorkspace(relative.ifBlank { path })
        _gitOpMessage.value = GitOpMessage.Ok("✓ 工作区已切到 $relative")
        refreshGitStatus()
    }

    /** 从给定 path 切工作区（DebugActionBus 用）。 */
    fun switchWorkspaceFromDebug(path: String) = switchWorkspace(path)

    /** 用给定 URL 探测是否有可用凭证（clone 对话框显示「将自动使用」提示）。 */
    fun probeCredential(url: String) {
        scope.launch(Dispatchers.IO) {
            val host = GitAuth.hostOf(url)
            val creds = gitPreferences.credentials.first()
            _matchedCredentialId.value = GitAuth.findCredential(creds, host)?.id
        }
    }

    // ===== AI 生成 commit message（万象独有：把 staged diff 交给当前激活模型，产出 Conventional Commits 消息）=====

    private val _aiCommit = MutableStateFlow<GitAiCommitState>(GitAiCommitState.Idle)
    val aiCommit: StateFlow<GitAiCommitState> = _aiCommit.asStateFlow()

    /** 触发一次 AI 生成。若正在 loading 忽略。 */
    fun aiGenerateCommitMessage() {
        if (_aiCommit.value is GitAiCommitState.Loading) return
        val ws = currentGitWs()
        _aiCommit.value = GitAiCommitState.Loading
        scope.launch(Dispatchers.IO) {
            // 8KB diff 上限：过大模型也读不完 + 计费高
            val diff = runGitRead(ws, "git diff --staged --stat && echo === && git diff --staged | head -c 8192")
                .orEmpty()
                .trim()
            if (diff.isBlank()) {
                _aiCommit.value = GitAiCommitState.Error("没有已暂存的改动，请先 stage 文件")
                return@launch
            }
            try {
                val model = providerClient.resolveModel()
                val messages = listOf(
                    ApiMessage(
                        role = "system",
                        content = "你是 Git 提交消息助手。根据用户给的 diff，写一条 Conventional Commits 风格的消息：" +
                            "第一行 `<type>(<scope>): <简短摘要>` 不超过 72 字符；空一行；body 说明「为什么这么做」而非「改了哪些行」（≤ 4 行）。" +
                            "type 从 feat/fix/refactor/docs/test/chore/perf/build/ci 里选。中文输出，禁止 markdown 代码块包裹，只输出消息本身。",
                    ),
                    ApiMessage(role = "user", content = "以下是 diff：\n\n$diff"),
                )
                val result = providerClient.chat(model, messages)
                val text = result.content?.trim().orEmpty()
                _aiCommit.value = if (text.isBlank()) GitAiCommitState.Error("模型返回空，试试再点一次")
                    else GitAiCommitState.Done(text)
            } catch (t: Throwable) {
                _aiCommit.value = GitAiCommitState.Error("AI 生成失败：${t.message ?: "未知"}")
            }
        }
    }

    fun consumeAiCommit() { _aiCommit.value = GitAiCommitState.Idle }

    // ===== 从 GitHub/Gitee 拉自己的仓库列表（clone 对话框里点「浏览我的仓库」用） =====

    private val _repoList = MutableStateFlow<GitRepoListState>(GitRepoListState.Idle)
    val repoList: StateFlow<GitRepoListState> = _repoList.asStateFlow()

    /** 用给定主机上第一条凭证打 provider 的 /user/repos API，取回名字+URL 列表。 */
    fun fetchUserRepos(host: String) {
        scope.launch(Dispatchers.IO) {
            _repoList.value = GitRepoListState.Loading
            val cred = gitPreferences.credentials.first().firstOrNull { it.host.equals(host, ignoreCase = true) }
            if (cred == null) {
                _repoList.value = GitRepoListState.Error("$host 无保存凭证，先到「凭证」页添加")
                return@launch
            }
            val quoted = shellQuote(cred.token)
            val (url, auth) = when {
                cred.host.contains("github", true) ->
                    "https://api.github.com/user/repos?per_page=100&sort=updated" to
                        "-H 'Authorization: Bearer $quoted' -H 'User-Agent: wanxiang-app'"
                cred.host.contains("gitee", true) ->
                    "https://gitee.com/api/v5/user/repos?per_page=100&sort=updated" to
                        "'access_token=$quoted'"
                cred.host.contains("gitlab", true) ->
                    "https://${cred.host.substringBefore('/')}/api/v4/projects?membership=true&per_page=100&order_by=last_activity_at" to
                        "-H 'PRIVATE-TOKEN: $quoted'"
                else -> {
                    _repoList.value = GitRepoListState.Error("暂不支持 $host 仓库列表")
                    return@launch
                }
            }
            val curlCmd = if (cred.host.contains("gitee", true)) {
                "curl -s --max-time 15 '$url?$auth'"
            } else {
                "curl -s --max-time 15 $auth '$url'"
            }
            val res = runCatching {
                linuxRuntime.execute(ShellCommand(commandLine = curlCmd, workingDirectory = "/root", timeoutMs = 25_000L))
            }
            val body = res.getOrNull()?.stdout?.trim().orEmpty()
            if (body.isBlank() || body.startsWith("<") || body.startsWith("curl:")) {
                _repoList.value = GitRepoListState.Error("拉取失败（沙箱可能没网，或 provider 拒了）。可以直接手动粘 URL")
                return@launch
            }
            runCatching {
                val json = JSONArray(body)
                val list = buildList {
                    for (i in 0 until json.length()) {
                        val obj = json.getJSONObject(i)
                        val name = obj.optString("full_name").ifBlank { obj.optString("path_with_namespace") }
                        val cloneUrl = obj.optString("clone_url").ifBlank { obj.optString("http_url_to_repo") }
                        val isPrivate = obj.optBoolean("private", false)
                        if (name.isNotBlank() && cloneUrl.isNotBlank()) add(GitRepoInfo(name, cloneUrl, isPrivate))
                    }
                }
                _repoList.value = if (list.isEmpty()) GitRepoListState.Error("账号下无仓库或返回空")
                    else GitRepoListState.Ready(list)
            }.onFailure {
                _repoList.value = GitRepoListState.Error("解析失败：${it.message}")
            }
        }
    }

    fun clearRepoList() { _repoList.value = GitRepoListState.Idle }

    // ===== Debug 通道辅助（adb 广播 E2E 验证用；仅 debug 构建的 DebugReceiver 会走到）=====

    /** 把任意文本塞进 git op 消息位（UI Snackbar 可见），用于诊断/GitRaw 输出回显。 */
    internal fun reportDebug(text: String) {
        _gitOpMessage.value = GitOpMessage.Error(text.take(600))
    }

    /** 在 git 工作区里跑一条读命令并返回合并输出（Debug GitRaw 用）。 */
    internal suspend fun rawRead(cmd: String): String =
        runGitRead(currentGitWs(), "$cmd 2>&1") ?: "<null>"

    /** 当前 git 工作区路径（Debug 诊断显示用）。 */
    internal fun debugWorkspacePath(): String = currentGitWs()

    /** 清空所有 git 凭证（Debug ClearCreds 用）。 */
    internal fun clearCredentials() {
        scope.launch(Dispatchers.IO) { gitPreferences.setCredentials(emptyList()) }
    }

    // ===== 凭证健康检查（点击每条凭证的验证图标 → 用 curl 打 provider /user 端点） =====

    private val _credHealth = MutableStateFlow<Map<String, GitCredHealth>>(emptyMap())
    val credHealth: StateFlow<Map<String, GitCredHealth>> = _credHealth.asStateFlow()

    /** 一键重验：对所有已保存凭证并发跑一次 verify（用户不必一条条点）。 */
    fun verifyAllCredentials() {
        scope.launch(Dispatchers.IO) {
            val list = gitPreferences.credentials.first()
            list.forEach { c -> verifyGitCredential(c.id) }
        }
    }

    fun verifyGitCredential(id: String) {
        scope.launch(Dispatchers.IO) {
            _credHealth.value = _credHealth.value + (id to GitCredHealth.Checking)
            val cred = gitPreferences.credentials.first().firstOrNull { it.id == id }
            if (cred == null) {
                _credHealth.value = _credHealth.value + (id to GitCredHealth.Unknown("凭证已删除"))
                return@launch
            }
            val (url, authMode) = when {
                cred.host.contains("github", true) -> "https://api.github.com/user" to "Bearer"
                cred.host.contains("gitee", true) -> "https://gitee.com/api/v5/user" to "query"
                cred.host.contains("gitlab", true) -> "https://${cred.host.substringBefore('/')}/api/v4/user" to "PRIVATE-TOKEN"
                else -> null to null
            }
            if (url == null) {
                _credHealth.value = _credHealth.value + (id to GitCredHealth.Unknown("未知主机：${cred.host}"))
                return@launch
            }
            val quoted = shellQuote(cred.token)
            val curlCmd = when (authMode) {
                "query" -> "curl -s -o /dev/null -w '%{http_code}' --max-time 12 '${url}?access_token=$quoted'"
                "PRIVATE-TOKEN" -> "curl -s -o /dev/null -w '%{http_code}' --max-time 12 -H 'PRIVATE-TOKEN: $quoted' '$url'"
                else -> "curl -s -o /dev/null -w '%{http_code}' --max-time 12 -H 'Authorization: Bearer $quoted' -H 'User-Agent: wanxiang-app' '$url'"
            }
            val result = runCatching {
                linuxRuntime.execute(ShellCommand(commandLine = curlCmd, workingDirectory = "/root", timeoutMs = 18_000L))
            }
            val code = result.getOrNull()?.stdout?.trim()?.takeLast(3).orEmpty()
            val h = when {
                code == "200" -> GitCredHealth.Ok()
                code == "401" || code == "403" -> GitCredHealth.Invalid(code)
                code == "000" || code.isBlank() || !code.all { it.isDigit() } -> GitCredHealth.Unknown("网络不通（可能手机没挂代理或 host 不可达）")
                else -> GitCredHealth.Unknown("HTTP $code")
            }
            _credHealth.value = _credHealth.value + (id to h)
        }
    }


    private fun currentGitWs(): String =
        workspaceProvider().ifBlank { "/workspace" }.let { if (it.startsWith("/")) it else "/workspace/$it" }

    /**
     * 打开提交详情（AiCode 弹层语义）：commitFiles 缓存优先，未加载则
     * `git show --name-status` 拉该提交改动文件清单。
     */
    fun loadCommitDetail(hash: String) {
        val safe = safeHash(hash) ?: return
        val ws = currentGitWs()
        _gitPanelState.value = _gitPanelState.value.copy(commitDetailHash = hash, loadingCommit = hash)
        if (_gitPanelState.value.commitFiles.containsKey(hash)) {
            _gitPanelState.value = _gitPanelState.value.copy(loadingCommit = null)
            return
        }
        scope.launch(Dispatchers.IO) {
            val raw = runGitRead(ws, "git show --name-status --format= $safe") ?: ""
            val files = raw.lines().mapNotNull { line ->
                val parts = line.trimEnd().split('\t')
                if (parts.size < 2) return@mapNotNull null
                val code = parts[0].firstOrNull()?.uppercaseChar() ?: return@mapNotNull null
                // 重命名 R100 旧路径 新路径：取末段（新路径）
                GitFileChange(code, parts.last().trim())
            }
            _gitPanelState.value = _gitPanelState.value.copy(
                commitFiles = _gitPanelState.value.commitFiles + (hash to files),
                loadingCommit = null,
            )
        }
    }

    /** 提交 hash 白名单：仅 7-64 位十六进制。被污染的 hash（换行/空格）拼进命令会被 sh 拆行执行，绝不允许。 */
    private fun safeHash(hash: String): String? = hash.takeIf { it.matches(Regex("[0-9a-fA-F]{7,64}")) }

    /** 详情弹层里点文件：进全屏 diff（`git diff <hash>^ <hash> -- path`；根提交回退 git show）。 */
    fun loadCommitFileDiff(hash: String, path: String) {
        val safe = safeHash(hash)
        if (safe == null) {
            _gitPanelState.value = _gitPanelState.value.copy(diffPath = "无效提交号", diffText = "提交 hash 格式非法：$hash", diffLoading = false)
            return
        }
        val ws = currentGitWs()
        _gitPanelState.value = _gitPanelState.value.copy(diffPath = "$hash · $path", diffText = null, diffLoading = true)
        scope.launch(Dispatchers.IO) {
            var diff = runGitRead(ws, "git diff ${safe}^ $safe -- ${shellQuote(path)}")
            if (diff == null || diff.startsWith("fatal:")) {
                // 根提交没有 hash^，回退 git show
                diff = runGitRead(ws, "git show $safe --format= -- ${shellQuote(path)}")
            }
            _gitPanelState.value = _gitPanelState.value.copy(
                diffText = diff?.takeIf { it.isNotBlank() } ?: "（无差异输出或为二进制文件）",
                diffLoading = false,
            )
        }
    }

    fun clearCommitDetail() {
        _gitPanelState.value = _gitPanelState.value.copy(
            commitDetailHash = null, commitDetailText = null, commitDetailLoading = false, loadingCommit = null,
        )
    }

    private fun runGitWrite(cmd: String) {
        val ws = workspaceProvider().ifBlank { "/workspace" }.let { if (it.startsWith("/")) it else "/workspace/$it" }
        runGitWriteRaw(ws, cmd, successMessage = null, successAction = null)
    }

    /** 通用「一次 git 写操作 + 完成时给 Snackbar 提示 + 可带一个撤销/还原动作」。 */
    private fun runGitWriteWithSuccess(
        cmd: String,
        successMessage: String,
        successAction: GitOpAction? = null,
    ) {
        val ws = workspaceProvider().ifBlank { "/workspace" }.let { if (it.startsWith("/")) it else "/workspace/$it" }
        runGitWriteRaw(ws, cmd, successMessage, successAction)
    }

    private fun runGitWriteRaw(
        ws: String,
        cmd: String,
        successMessage: String?,
        successAction: GitOpAction?,
    ) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                linuxRuntime.execute(
                    ShellCommand(
                        commandLine = "$cmd 2>&1",
                        workingDirectory = ws,
                        timeoutMs = 30_000L,
                    ),
                )
            }
            val r = result.getOrNull()
            val out = ((r?.stdout ?: "") + "\n" + (r?.stderr ?: "")).trim()
            if (r != null && r.isSuccess) {
                if (successMessage != null) {
                    _gitOpMessage.value = GitOpMessage.Ok(successMessage, successAction)
                }
            } else {
                val op = cmd.substringAfter("git ").substringBefore(' ').ifBlank { "git" }
                _gitOpMessage.value = GitOpMessage.Error(
                    "$op 失败：\n" + out.take(500),
                    action = GitOpAction.CopyError,
                )
            }
            refreshGitStatus()
        }
    }
}
