package top.wkbin.taixu.service

import kotlin.math.absoluteValue

/**
 * Agent 常驻通知的 Notification id 分配。
 *
 * 抽成纯函数（不依赖 Context / Service）的原因：id 空间是**全进程共享**的，
 * 一旦与其它常驻通知的 id 重叠，`NotificationManager.notify(id, ...)` 会静默改写另一条通知
 * （Android 只按 id 去重，不产生任何异常），表现为"FTP / Web 协作台通知莫名其妙变了内容"。
 * 这类重叠靠肉眼审查 id 常量很难发现，必须能被单测拦住。
 *
 * 全仓已占用的 id（改动本文件前请同步核对）：
 * | 来源 | id |
 * |---|---|
 * | RuntimeForegroundService | 1001 |
 * | AgentForegroundService（primary） | 2001（本文件 [PRIMARY]） |
 * | AdbNotificationManager | 2005 |
 * | FtpServiceManager | 2121 |
 * | WebChatBridgeServer | 8899 |
 * | WorkflowHudService | 20091 |
 * | ToolNotificationNotifier.toolNotificationId | 20000 ~ 52767 |
 * | ToolNotificationNotifier.buildNotificationId | 30000 ~ 62767 |
 */
internal object AgentNotificationIds {

    /** 主会话（当前通知栏上代表本服务前台通知的那一个）使用的固定 id。 */
    const val PRIMARY = 2001

    /**
     * 次级会话 id 区间起点，取值 [SECONDARY_BASE, SECONDARY_BASE + SECONDARY_SLOTS)。
     *
     * 历史实现是 `PRIMARY + hash % 9000 + 1` → [2002, 11001]，与 FtpServiceManager(2121)、
     * WebChatBridgeServer(8899) 直接重叠。迁到 300001 之后同时避开
     * [20000, 52767] / [30000, 62767] 两个工具通知区间。
     */
    const val SECONDARY_BASE = 300_001
    const val SECONDARY_SLOTS = 8_000

    /**
     * @param sessionId 目标会话
     * @param primarySessionId 当前代表前台通知的会话（`null` 表示集合为空）
     *
     * 语义与修复前一致：primary 会话用 [PRIMARY]，其余落 [SECONDARY_BASE] 起的高位区间。
     * 调用方必须在**清空活跃集合之前**算好所有会话的 id —— 集合清空后 primary 变为 null，
     * 所有会话都会退化成 [PRIMARY]，同轮内多次 notify 相互覆盖，只剩最后一条可见。
     */
    fun forSession(sessionId: String, primarySessionId: String?): Int =
        if (primarySessionId == null || primarySessionId == sessionId) {
            PRIMARY
        } else {
            SECONDARY_BASE + (sessionId.hashCode().absoluteValue % SECONDARY_SLOTS)
        }
}
