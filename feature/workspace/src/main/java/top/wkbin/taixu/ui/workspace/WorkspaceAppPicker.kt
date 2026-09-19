package top.wkbin.taixu.ui.workspace

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.feature.workspace.R
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTextButton as TextButton

// ==================== APK 逆向模板：已安装应用选择器 ====================

/** 已安装应用的应用名（label），失败时回退包名。 */
internal fun ApplicationInfo.appLabel(context: Context): String =
    runCatching { loadLabel(context.packageManager).toString() }.getOrDefault(packageName)

/** 通过 SAF 查询 URI 的显示文件名。 */
internal fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

/** APK 逆向模板：从宿主已安装应用中选择逆向目标。 */
@Composable
internal fun AppPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (ApplicationInfo) -> Unit,
) {
    val context = LocalContext.current
    // 枚举 + loadLabel（IPC）在 200+ 应用设备上耗时数百毫秒，不能放组合期主线程；
    // label 预解析进列表，避免每个 item 组合时重复 loadLabel。
    // null = 仍在加载。
    val appState by androidx.compose.runtime.produceState<List<Pair<ApplicationInfo, String>>?>(
        initialValue = null,
        key1 = context,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.packageManager.getInstalledApplications(0)
                    .filter { it.sourceDir != null && java.io.File(it.sourceDir).isFile }
                    .map { it to it.appLabel(context).lowercase() }
                    .sortedBy { it.second }
            }.getOrDefault(emptyList())
        }
    }
    val appsLoading = appState == null
    val apps = appState.orEmpty()
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_choose_installed_app), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (appsLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        RuntimeCircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else if (apps.isEmpty()) {
                    Text(
                        stringResource(R.string.workspace_apps_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.workspace_choose_app_description),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(apps, key = { it.first.packageName }) { (app, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(app) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    RuntimeIcon(
                                        RuntimeIconName.Package,
                                        Modifier.size(22.dp),
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_cancel)) } },
    )
}
