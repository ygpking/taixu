package top.wkbin.taixu.harness.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import top.wkbin.taixu.core.model.workflow.WorkflowRuntimeContext
import org.junit.Test

/**
 * 工作流变量替换的语义回归测试。
 *
 * 背景（真实缺陷，非假想）：workflow 包内曾有**两个同名同签名的 `interpolate`**，
 * 语义却相反 ——
 *
 * | 位置 | 行为 |
 * |---|---|
 * | `HostActionNodeExecutor.kt` 的顶层 `interpolate` | **原样替换**，不引号化 |
 * | `BuiltinNodeExecutors.kt` 的私有 `interpolate` | 结果走 `posixQuote` |
 *
 * 复制粘贴时带上错误的那一版语义，就会让「外部可控的变量值」直接拼进 shell 命令串。
 * 本次把前者改名为 `interpolateRaw` 以消除同名歧义，并用本测试钉死两边语义。
 */
class HostActionShellQuotingTest {

    private fun context(vars: Map<String, String>) = WorkflowRuntimeContext(
        executionId = "e",
        workflowId = "w",
        workspacePath = "/workspace/demo",
        globalVariables = vars,
    )

    @Test
    fun `raw interpolation substitutes plain values without quoting`() {
        val out = interpolateRaw(
            "/system/bin/cmd wifi ${'$'}{PACKAGE}",
            context(mapOf("PACKAGE" to "com.example.app")),
        )
        assertEquals("/system/bin/cmd wifi com.example.app", out)
    }

    /**
     * 关键差异断言：外部变量带命令分隔符时 `interpolateRaw` **原样透传**。
     * 这正是"调用方必须自己包引号"的证据 —— 也是本次给
     * `shell` / `cmd` / `--user` 三处补引号化或白名单校验的原因。
     */
    @Test
    fun `raw interpolation passes hostile values through verbatim`() {
        val out = interpolateRaw(
            "echo ${'$'}{V}",
            context(mapOf("V" to "0; rm -rf /data")),
        )
        assertEquals("echo 0; rm -rf /data", out)
        assertFalse("原样替换不会自动加引号（调用方必须显式保护）", out.contains("'"))
    }

    @Test
    fun `workspace path is substituted`() {
        val ctx = WorkflowRuntimeContext(
            executionId = "e",
            workflowId = "w",
            workspacePath = "/workspace/proj",
        )
        assertEquals("cd /workspace/proj", interpolateRaw("cd ${'$'}{WORKSPACE_PATH}", ctx))
    }

    @Test
    fun `previous output is substituted from upstream nodes`() {
        val ctx = WorkflowRuntimeContext(
            executionId = "e",
            workflowId = "w",
            workspacePath = "/workspace/proj",
            nodeOutputs = mapOf(
                "build" to top.wkbin.taixu.core.model.workflow.NodeExecutionOutput(
                    top.wkbin.taixu.core.model.workflow.NodeRunStatus.SUCCESS,
                    textOutput = "构建成功",
                ),
            ),
        )
        assertEquals("构建成功", interpolateRaw("${'$'}{previous.output}", ctx))
    }

    @Test
    fun `unknown variables resolve to empty rather than leaking the token`() {
        assertEquals("x=", interpolateRaw("x=${'$'}{NOPE}", context(emptyMap())))
    }
}
