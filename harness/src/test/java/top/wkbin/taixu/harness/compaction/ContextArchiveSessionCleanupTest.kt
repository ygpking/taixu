package top.wkbin.taixu.harness.compaction

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.UserMessage

/**
 * 会话归档目录清理 + 目录名规则一致性的测试。
 *
 * 缺陷：`trim` 只做单会话内的体积控制（保留最近 20 个文件），**会话级目录本身从无人删**。
 * 会话 id 是 UUID 且不复用 → 每删一个会话，`<workspace>/.taixu-context/<sessionId>/`
 * 就永久留在**用户可见的工作区**里（不是数据库孤儿，用户直接看得见）。
 *
 * 同族对照：`CheckpointStore.delete(sessionId)` 早已做同类目录的 `deleteRecursively()`。
 *
 * ⚠️ 最危险的失败模式是**目录名规则不一致**：archive 与 delete 各写一套 filter，
 * 删除会指向不存在的目录、静默 no-op —— 归档越积越多，却"看起来已经清了"。
 * 本测试专门锁死两处共用同一规则。
 */
class ContextArchiveSessionCleanupTest {

    private lateinit var workspace: File

    @Before
    fun setUp() {
        workspace = Files.createTempDirectory("taixu-archive-test").toFile()
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private fun archiveOne(sessionId: String): String? = ContextArchive.archive(
        workspacePath = workspace.absolutePath,
        sessionId = sessionId,
        messages = listOf(UserMessage(id = "u1", createdAt = 1L, text = "hi")),
        reason = "test",
        archiveId = "1700000000-1-abcd",
    )

    private fun sessionDir(sessionId: String) =
        File(File(workspace, ContextArchive.DIR_NAME), ContextArchive.safeSessionId(sessionId))

    @Test
    fun `archive creates the session directory and cleanup removes it`() {
        val sessionId = "sess-archive-1"
        val relative = archiveOne(sessionId)
        assertTrue("归档应成功", relative != null)
        assertTrue("目录应存在", sessionDir(sessionId).isDirectory)

        val deleted = ContextArchive.deleteSessionArchive(workspace.absolutePath, sessionId)

        assertTrue("应报告已删除", deleted)
        assertFalse("会话归档目录必须被清掉", sessionDir(sessionId).exists())
    }

    @Test
    fun `cleanup does not touch other sessions`() {
        archiveOne("keep-a")
        archiveOne("keep-b")
        archiveOne("drop-me")

        ContextArchive.deleteSessionArchive(workspace.absolutePath, "drop-me")

        assertTrue(sessionDir("keep-a").isDirectory)
        assertTrue(sessionDir("keep-b").isDirectory)
        assertFalse(sessionDir("drop-me").exists())
    }

    @Test
    fun `cleanup of a session without archives is a no-op and reports false`() {
        assertFalse(ContextArchive.deleteSessionArchive(workspace.absolutePath, "never-existed"))
    }

    @Test
    fun `blank workspace or session id is refused`() {
        assertFalse(ContextArchive.deleteSessionArchive(null, "s"))
        assertFalse(ContextArchive.deleteSessionArchive("", "s"))
        assertFalse(ContextArchive.deleteSessionArchive(workspace.absolutePath, ""))
        assertFalse(ContextArchive.deleteSessionArchive(workspace.absolutePath, "   "))
    }

    /**
     * 目录名规则必须与 archive 完全一致。
     * 用带斜杠/奇怪字符的 id 验证：两处若各写一套 filter，删除就会指向别的目录。
     */
    @Test
    fun `delete resolves the same directory that archive created`() {
        val weird = "sess/with:weird*chars"
        val relative = archiveOne(weird)
        assertTrue("归档应成功", relative != null)

        // archive 返回的相对路径里的目录名，必须等于 delete 解析出的目录名
        val archivedDirName = relative!!.split('/')[1]
        assertEquals(
            "archive 与 delete 必须用同一套目录名规则",
            archivedDirName,
            ContextArchive.safeSessionId(weird),
        )
        assertTrue(ContextArchive.deleteSessionArchive(workspace.absolutePath, weird))
        assertFalse(sessionDir(weird).exists())
    }

    @Test
    fun `safeSessionId never yields a blank or path-traversing name`() {
        assertEquals("session", ContextArchive.safeSessionId(""))
        assertEquals("session", ContextArchive.safeSessionId("!!!"))
        // 路径穿越字符必须被滤掉，否则删除会指到工作区外
        assertFalse(ContextArchive.safeSessionId("../evil").contains(".."))
        assertFalse(ContextArchive.safeSessionId("../evil").contains("/"))
        assertFalse(ContextArchive.safeSessionId("a/b").contains("/"))
    }

    @Test
    fun `cleanup tolerates a message-bearing archive and leaves workspace root intact`() {
        ContextArchive.archive(
            workspacePath = workspace.absolutePath,
            sessionId = "s-multi",
            messages = listOf(
                UserMessage(id = "u1", createdAt = 1L, text = "一"),
                AssistantText(id = "a1", createdAt = 2L, text = "二"),
            ),
            reason = "compaction",
            archiveId = "1700000001-2-efgh",
        )
        assertTrue(ContextArchive.deleteSessionArchive(workspace.absolutePath, "s-multi"))
        assertTrue("工作区根目录本身不得被删", workspace.isDirectory)
    }
}
