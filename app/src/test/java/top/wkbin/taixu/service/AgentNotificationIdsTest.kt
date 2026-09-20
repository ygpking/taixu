package top.wkbin.taixu.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知 id 分配的回归测试。
 *
 * 背景（真实缺陷，非假想）：
 * 1. 次级会话 id 原为 `PRIMARY(2001) + hash % 9000 + 1` → [2002, 11001]，
 *    与 FtpServiceManager(2121) / WebChatBridgeServer(8899) 正面重叠。
 *    重叠时 `notify(id, ...)` 会静默改写另一条通知（Android 只按 id 去重，不报错）。
 * 2. 完成通知的 id 原在 `activeNotifSessionIds.clear()` **之后**计算，
 *    集合已空 → primary 为 null → 每个会话都退化成 PRIMARY(2001)，
 *    同轮内多个会话完成时相互覆盖，除最后一条外全部丢失。
 */
class AgentNotificationIdsTest {

    /** 全仓其它常驻通知使用的固定 id（从各 Service/Manager 的常量抄来）。 */
    private val reservedIds = setOf(1001, 2001, 2005, 2121, 8899, 20091)

    /** 与之重叠会造成静默改写的区间：工具安装/构建通知。 */
    private val toolNotificationRange = 20_000..62_767

    @Test
    fun `primary session uses the reserved primary id`() {
        assertEquals(AgentNotificationIds.PRIMARY, AgentNotificationIds.forSession("abc", "abc"))
    }

    @Test
    fun `null primary falls back to primary id (empty set semantics)`() {
        assertEquals(AgentNotificationIds.PRIMARY, AgentNotificationIds.forSession("abc", null))
    }

    @Test
    fun `secondary session id never collides with other notifications in the app`() {
        // 覆盖大批与会话名相似的 id，验证不会落进任何已占用区
        val sample = generateSequence(0) { it + 1 }.take(2_000)
            .map { "session-$it" } + listOf("会话甲", "会话乙", "sess-1", "sess-2", "sess-3")
        for (sessionId in sample) {
            val id = AgentNotificationIds.forSession(sessionId, primarySessionId = "other")
            assertTrue("$sessionId 落到保留 id $id", id !in reservedIds)
            assertTrue("$sessionId 落到工具通知区 $id", id !in toolNotificationRange)
            assertTrue(
                "$sessionId 越出次级区间 $id",
                id in AgentNotificationIds.SECONDARY_BASE until
                    (AgentNotificationIds.SECONDARY_BASE + AgentNotificationIds.SECONDARY_SLOTS),
            )
        }
    }

    @Test
    fun `secondary ids stay distinct for distinct sessions`() {
        val a = AgentNotificationIds.forSession("session-a", primarySessionId = "other")
        val b = AgentNotificationIds.forSession("session-b", primarySessionId = "other")
        assertNotEquals(a, b)
    }

    /**
     * 锁死"必须在清空集合**之前**算 id"这条语义：
     * 换 primary 之后同一个会话的 id 会变，正因为会变，才显式体现"清空后全部退化成 PRIMARY"。
     */
    @Test
    fun `clearing the active set before computing ids collapses every session onto primary`() {
        val sessionIds = listOf("s-1", "s-2", "s-3")
        val beforeClear = sessionIds.map { AgentNotificationIds.forSession(it, primarySessionId = "s-1") }
        val afterClear = sessionIds.map { AgentNotificationIds.forSession(it, primarySessionId = null) }

        assertEquals(listOf(AgentNotificationIds.PRIMARY, beforeClear[1], beforeClear[2]), beforeClear)
        assertTrue("清空后应全部退化为 PRIMARY", afterClear.all { it == AgentNotificationIds.PRIMARY })
        assertEquals(
            "清空后再算会让 3 个会话共用同一个 id（相互覆盖，只剩最后一条可见）",
            1,
            afterClear.toSet().size,
        )
    }
}
