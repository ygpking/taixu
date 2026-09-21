package top.wkbin.taixu.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import top.wkbin.taixu.harness.HarnessLoop
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 处理通知栏【回复】输入框：把用户的下一条指令交给 Agent 继续执行。 */
@AndroidEntryPoint
class AgentReplyReceiver : BroadcastReceiver() {

    /**
     * 必须是 dagger.Lazy。BroadcastReceiver.onReceive 跑在主线程，Hilt 注入 eager 字段时
     * 必须先在主线程把 [HarnessLoop] 这张重图构造出来（Room / ProviderClient / ToolExecutor /
     * MCP 等，见 [top.wkbin.taixu.TaiXuApplication] 里对同一问题的注释）；而本方法紧接着就调用
     * startForegroundService() —— 主线程被构造占用多久，前台服务的上报就被推迟多久，
     * 一旦超出系统给的窗口即触发 ForegroundServiceDidNotStartInTimeException。
     */
    @Inject lateinit var harnessLoopLazy: Lazy<HarnessLoop>

    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AgentForegroundService.KEY_REPLY)
            ?.toString()
            ?.trim()
        val targetSessionId = intent.getStringExtra(AgentForegroundService.EXTRA_SESSION_ID)
        if (reply.isNullOrBlank()) return
        // 先拉起前台服务（保持后台存活），再投递指令给对应 Agent 会话。
        AgentForegroundService.startFromReply(context, targetSessionId)
        // 只把「取依赖」这一步挪出主线程：首次 get() 会构造整张重图，放主线程就是上面注释说的隐患。
        // send() 本身是非阻塞的（内部丢进 HarnessLoop 自己的 scope），所以无需 goAsync 延长广播生命周期。
        // 作用域与广播、组件生命周期无关；投递是一次性的，跑完即结束。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { harnessLoopLazy.get().send(reply, targetSessionId) }
                .onFailure { Log.w(TAG, "投递通知栏回复失败", it) }
        }
    }

    private companion object {
        const val TAG = "AgentReplyReceiver"
    }
}
