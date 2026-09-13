package top.wkbin.taixu.ui.chat

import top.wkbin.taixu.ui.chat.git.GitGraph

/**
 * Git 可视化工作台 7 个 UI state 模型。
 *
 * 来源：万象 Wanxiang `feature/chat/.../ChatViewModel.kt` L2287~L2526 内嵌 data class，
 * 2026-09-13 抽出独立文件，包名改为本 fork `top.wkbin.taixu.ui.chat`。
 */

/** 仓库列表项。 */
data class GitRepoInfo(val fullName: String, val cloneUrl: String, val private: Boolean)

/** 仓库列表加载的三态。 */
sealed interface GitRepoListState {
    data object Idle : GitRepoListState
    data object Loading : GitRepoListState
    data class Ready(val repos: List<GitRepoInfo>) : GitRepoListState
    data class Error(val reason: String) : GitRepoListState
}

/** Git 一次性操作反馈（克隆/拉取/推送）：给 UI Snackbar 消费。 */
sealed interface GitOpMessage {
    data object Idle : GitOpMessage
    data class Busy(val label: String) : GitOpMessage
    data class Ok(val message: String, val action: GitOpAction? = null) : GitOpMessage
    data class Error(val message: String, val action: GitOpAction? = null) : GitOpMessage
}

/** 反馈消息里可点的动作（Snackbar 的 action 按钮）。 */
sealed interface GitOpAction {
    /** 一键把工作区切到某个子目录（clone 完成后）。 */
    data class SwitchWorkspaceTo(val path: String) : GitOpAction
    /** 同名目录已存在时的一键清空再试。 */
    data class RetryWithClean(val url: String, val targetDir: String) : GitOpAction
    /** 网络错误重试。 */
    data class RetrySame(val command: String) : GitOpAction
    /** 复制错误详情到剪贴板（用户可粘给助手/日志）。 */
    data object CopyError : GitOpAction
    /** 撤销上一步 rename（Snackbar 点一下就换回去）。 */
    data class UndoRename(val oldName: String, val newName: String) : GitOpAction
    /** stash push 后一键 pop。 */
    data object StashPop : GitOpAction
}

/** 流式进度条数据。git 每次输出进度 chunk 更新一次。 */
data class GitProgress(
    val label: String,
    val percent: Int? = null,
    val objects: String? = null,
    val transfer: String? = null,
    val raw: String? = null,
)

/** 单个文件改动记录。 */
data class GitFileChange(
    val status: Char,
    val path: String,
)

/**
 * Git 面板完整状态：当前会话工作区的分支、改动、分支列表、提交历史与拓扑图。
 * 不跨会话共享。
 */
data class GitPanelState(
    val loading: Boolean = false,
    val branch: String? = null,
    /** 相对 upstream 的 ahead/behind 计数（无 upstream 或未同步为 null）。 */
    val aheadBehind: Pair<Int, Int>? = null,
    val staged: List<GitFileChange> = emptyList(),
    val unstaged: List<GitFileChange> = emptyList(),
    val untracked: List<String> = emptyList(),
    val localBranches: List<String> = emptyList(),
    val remoteBranches: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** AiCode 风格提交拓扑图（泳道/边/refs）。 */
    val graph: GitGraph = GitGraph.EMPTY,
    /** hash → 该提交改动文件清单（点开详情时懒加载缓存）。 */
    val commitFiles: Map<String, List<GitFileChange>> = emptyMap(),
    /** 分页拉取更早提交中。 */
    val graphLoadingMore: Boolean = false,
    /** 正在加载文件清单的提交 hash。 */
    val loadingCommit: String? = null,
    val hasIdentity: Boolean = false,
    val hasRemote: Boolean = false,
    /** 当前 `git stash list` 条数（>0 时可 pop）。 */
    val stashCount: Int = 0,
    /** 浅克隆仓库（--depth 1 等）：提交图只有少数几条，图页提供「加载完整历史」反浅克隆。 */
    val isShallow: Boolean = false,
    val notARepo: Boolean = false,
    val diffPath: String? = null,
    val diffText: String? = null,
    val diffLoading: Boolean = false,
    /** 本地脏时 pull 前需要用户二次确认（可能被覆盖或产生冲突）。 */
    val pullDirtyConfirm: Boolean = false,
    /** 本地脏时切分支前的二次确认（branch 名）。非空 = 需确认。 */
    val pendingCheckout: String? = null,
    val commitDetailHash: String? = null,
    val commitDetailText: String? = null,
    val commitDetailLoading: Boolean = false,
    val error: String? = null,
)

/**
 * Shell 单引号转义（' 替换为 '\''）。
 * 在 Git 命令拼接时统一调用，避免路径含特殊字符时注入 shell。
 */
internal fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

/** AI 生成 commit 消息的状态机：Idle / Loading / Done(text) / Error(reason)。 */
sealed interface GitAiCommitState {
    data object Idle : GitAiCommitState
    data object Loading : GitAiCommitState
    data class Done(val message: String) : GitAiCommitState
    data class Error(val reason: String) : GitAiCommitState
}

/** 凭证健康检查的四种状态（checkedAtMillis 用于「N 分钟前验证过」显示）。 */
sealed interface GitCredHealth {
    val checkedAtMillis: Long get() = 0L
    data class Ok(override val checkedAtMillis: Long = System.currentTimeMillis()) : GitCredHealth
    data class Invalid(val code: String, override val checkedAtMillis: Long = System.currentTimeMillis()) : GitCredHealth
    data class Unknown(val reason: String, override val checkedAtMillis: Long = System.currentTimeMillis()) : GitCredHealth
    data object Checking : GitCredHealth
}
