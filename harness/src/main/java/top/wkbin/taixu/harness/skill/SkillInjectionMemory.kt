package top.wkbin.taixu.harness.skill

import java.util.concurrent.ConcurrentHashMap

/**
 * 会话级「本轮已自动注入哪些技能」的**进程内**记忆，用于跨轮粘性。
 *
 * 背景：机械预匹配每轮按 `latestUserMessage` 全量重算，追问轮的关键词一旦不复现，
 * 已注入的指导规则就静默丢失——等于这套机制要消灭的"无声遗漏"每隔一轮复发一次。
 *
 * 只存 id、只在进程内（会话树是持久真相源，重启后由摘要/目录兜底），
 * 不碰 DB、不加 schema。删会话时由 HarnessLoop 调 [forget] 回收。
 */
object SkillInjectionMemory {

    private val sticky = ConcurrentHashMap<String, MutableSet<String>>()

    /** 记录本轮自动注入的技能，作为下一轮的粘性候选。 */
    fun record(sessionId: String, autoMatchedIds: Collection<String>) {
        if (sessionId.isBlank()) return
        if (autoMatchedIds.isEmpty()) return
        sticky.getOrPut(sessionId) { mutableSetOf() }.addAll(autoMatchedIds)
    }

    /** 上一轮自动注入、本轮应继续注入的技能 id。 */
    fun stickyIds(sessionId: String): Set<String> =
        if (sessionId.isBlank()) emptySet() else sticky[sessionId]?.toSet() ?: emptySet()

    /** 会话删除时回收，避免长跑设备上按 sessionId 无界增长。 */
    fun forget(sessionId: String) {
        sticky.remove(sessionId)
    }

    /** 测试用：清空。 */
    fun reset() {
        sticky.clear()
    }
}
