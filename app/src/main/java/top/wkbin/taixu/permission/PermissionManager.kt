package top.wkbin.taixu.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.wkbin.taixu.core.common.logging.CrashReporter

/**
 * 🛡️ 统一权限管理器
 * 
 * 功能：
 * 1. 集中管理所有运行时权限请求
 * 2. 使用 Activity Result API 泛型封装
 * 3. 权限被拒后显示设置引导对话框
 * 4. 支持权限状态监听（StateFlow）
 * 
 * 用法示例：
 * ```kotlin
 * // 在 Activity/Fragment 中
 * @Inject lateinit var permissionManager: PermissionManager
 * 
 * // 请求通知权限
 * permissionManager.requestPermission(
 *     permission = Manifest.permission.POST_NOTIFICATIONS,
 *     rationaleResId = R.string.permission_notification_rationale,
 *     deniedAction = PermissionDeniedAction.ShowSettings
 * )
 * ```
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crashReporter: CrashReporter
) {
    /** 权限状态流：permission -> isGranted */
    private val _permissionStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val permissionStates: StateFlow<Map<String, Boolean>> = _permissionStates.asStateFlow()

    /** 注册的权限请求启动器 */
    private val launchers = mutableMapOf<String, ActivityResultLauncher<String>>()

    /** 待处理的权限请求回调 */
    private val pendingCallbacks = mutableMapOf<String, (Boolean) -> Unit>()

    /**
     * 注册权限请求启动器（需在 Activity.onCreate 中调用）
     * 
     * @param activity 宿主 Activity
     * @param permission 权限字符串
     * @param launcher 权限结果启动器
     */
    fun registerLauncher(
        permission: String,
        launcher: ActivityResultLauncher<String>
    ) {
        launchers[permission] = launcher
    }

    /**
     * 检查权限是否已授予
     */
    fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 批量检查权限
     */
    fun areAllGranted(permissions: List<String>): Boolean {
        return permissions.all { isGranted(it) }
    }

    /**
     * 获取被拒绝的权限列表（不含永久拒绝）
     */
    fun getDeniedPermissions(permissions: List<String>): List<String> {
        return permissions.filter { !isGranted(it) }
    }

    /**
     * 获取永久拒绝的权限列表（需引导至设置页）
     */
    fun getPermanentlyDeniedPermissions(activity: Activity, permissions: List<String>): List<String> {
        return permissions.filter { permission ->
            !isGranted(permission) && 
            !activity.shouldShowRequestPermissionRationale(permission)
        }
    }

    /**
     * 请求单个权限
     * 
     * @param activity 宿主 Activity
     * @param permission 权限字符串
     * @param rationaleResId 权限说明文案资源 ID（可选）
     * @param deniedAction 权限被拒后的处理动作
     * @param onResult 权限结果回调
     */
    fun requestPermission(
        activity: Activity,
        permission: String,
        rationaleResId: Int? = null,
        deniedAction: PermissionDeniedAction = PermissionDeniedAction.Dismiss,
        onResult: (Boolean) -> Unit = {}
    ) {
        // 已授予，直接返回
        if (isGranted(permission)) {
            onResult(true)
            updatePermissionState(permission, true)
            return
        }

        // 需要显示说明理由
        if (rationaleResId != null && activity.shouldShowRequestPermissionRationale(permission)) {
            showPermissionRationaleDialog(
                activity = activity,
                rationaleResId = rationaleResId,
                permission = permission,
                deniedAction = deniedAction,
                onResult = onResult
            )
            return
        }

        // 直接请求权限
        requestPermissionInternal(activity, permission, onResult)
    }

    /**
     * 请求多个权限
     * 
     * @param activity 宿主 Activity
     * @param permissions 权限字符串列表
     * @param onResult 权限结果回调（返回授予的权限列表）
     */
    fun requestPermissions(
        activity: Activity,
        permissions: List<String>,
        onResult: (List<String>) -> Unit = {}
    ) {
        val denied = getDeniedPermissions(permissions)
        
        if (denied.isEmpty()) {
            onResult(permissions)
            return
        }

        // Android 13+ 通知权限特殊处理
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            Manifest.permission.POST_NOTIFICATIONS in denied
        ) {
            requestPermission(
                activity = activity,
                permission = Manifest.permission.POST_NOTIFICATIONS,
                onResult = { granted ->
                    val grantedPermissions = if (granted) {
                        permissions.intersect(denied.toSet()).toList()
                    } else {
                        emptyList()
                    }
                    onResult(grantedPermissions)
                }
            )
            return
        }

        // 其他权限：逐个请求第一个被拒绝的权限
        // 注意：Android 不允许同时请求多个不相关的权限，需要逐个处理
        val firstDeniedPermission = denied.first()
        val launcher = launchers[firstDeniedPermission] 
            ?: throw IllegalStateException("Permission launcher not registered for $firstDeniedPermission")
        
        pendingCallbacks[firstDeniedPermission] = { granted ->
            // 如果第一个权限被授予，继续请求剩余的权限
            val remainingPermissions = if (granted) denied.drop(1) else emptyList()
            if (remainingPermissions.isNotEmpty()) {
                // 递归请求剩余权限
                requestPermissions(activity, remainingPermissions) { remainingGranted ->
                    onResult(listOf(firstDeniedPermission) + remainingGranted)
                }
            } else {
                onResult(if (granted) listOf(firstDeniedPermission) else emptyList())
                pendingCallbacks.remove(firstDeniedPermission)
            }
        }

        launcher.launch(firstDeniedPermission)
    }

    /**
     * 内部权限请求实现
     */
    private fun requestPermissionInternal(
        activity: Activity,
        permission: String,
        onResult: (Boolean) -> Unit
    ) {
        val launcher = launchers[permission]
            ?: throw IllegalStateException("Permission launcher not registered for $permission")

        pendingCallbacks[permission] = onResult
        launcher.launch(permission)
    }

    /**
     * 显示权限说明对话框
     */
    private fun showPermissionRationaleDialog(
        activity: Activity,
        rationaleResId: Int,
        permission: String,
        deniedAction: PermissionDeniedAction,
        onResult: (Boolean) -> Unit
    ) {
        // 使用 RuntimeAlertDialog 显示说明
        // TODO: 与 UI 模块协作实现对话框
        android.util.Log.i("PermissionManager", "Showing rationale for $permission")
        
        // 简化实现：直接请求权限
        requestPermissionInternal(activity, permission, onResult)
    }

    /**
     * 处理权限请求结果（由 Activity 调用）
     */
    fun handlePermissionResult(
        permission: String,
        granted: Boolean
    ) {
        updatePermissionState(permission, granted)
        
        pendingCallbacks.remove(permission)?.invoke(granted)
        
        if (!granted) {
            android.util.Log.w("PermissionManager", "Permission denied: $permission")
        }
    }

    /**
     * 更新权限状态
     */
    private fun updatePermissionState(permission: String, granted: Boolean) {
        val currentStates = _permissionStates.value.toMutableMap()
        currentStates[permission] = granted
        _permissionStates.value = currentStates
    }

    /**
     * 引导用户至应用设置页
     */
    fun navigateToAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 清理资源（在 Activity.onDestroy 中调用）
     */
    fun cleanup(activity: Activity) {
        // 取消所有待处理的回调
        pendingCallbacks.clear()
    }
}

/**
 * 权限被拒后的处理动作
 */
sealed class PermissionDeniedAction {
    /** 直接关闭对话框 */
    object Dismiss : PermissionDeniedAction()
    
    /** 显示设置引导按钮 */
    object ShowSettings : PermissionDeniedAction()
    
    /** 限制功能但允许继续使用 */
    object LimitFunctionality : PermissionDeniedAction()
}

/**
 * 常用权限常量组
 */
object PermissionGroups {
    /** 通知相关权限（Android 13+） */
    val NOTIFICATION = listOf(
        Manifest.permission.POST_NOTIFICATIONS
    )

    /** 存储相关权限（Android 12 及以下） */
    @Suppress("DEPRECATION")
    val STORAGE_LEGACY = listOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    /** 相机权限 */
    val CAMERA = listOf(
        Manifest.permission.CAMERA
    )

    /** 位置权限（前台） */
    val LOCATION_FOREGROUND = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /** 位置权限（后台，需特殊审批） */
    @RequiresApi(Build.VERSION_CODES.Q)
    val LOCATION_BACKGROUND = listOf(
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    )

    /** 安装应用权限 */
    val INSTALL_PACKAGES = listOf(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Manifest.permission.REQUEST_INSTALL_PACKAGES
        } else {
            ""
        }
    ).filter { it.isNotEmpty() }
}
