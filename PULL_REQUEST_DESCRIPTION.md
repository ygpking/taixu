# 🏗️ 太墟架构改进：核心组件增强与可观测性提升

## 📋 概述
本PR实现了太墟(TaiXu)项目7个核心模块的21项架构改进建议中的5个高优先级组件，显著提升系统的权限管理、资源控制、执行安全、存储优化和网络可观测性。

## 🎯 改进目标
- ✅ **统一权限管理**：解决分散的权限请求逻辑，提供状态监听和设置引导
- ✅ **子代理资源隔离**：防止恶意或异常Agent耗尽系统资源
- ✅ **Shell执行安全**：避免无限期阻塞，增强运行时稳定性
- ✅ **临时文件清理**：防止存储空间泄漏，自动回收过期数据
- ✅ **网络日志结构化**：提升调试效率，自动脱敏敏感信息

---

## 🚀 新增组件

### 1. `app/permission/PermissionManager.kt` (332行)
**功能**：统一的Android权限管理中心
- 📦 **Hilt DI集成**：单例模式，全局注入
- 🔄 **StateFlow状态监听**：实时响应权限变化
- 🧭 **设置引导**：权限被拒时自动跳转系统设置
- 🛡️ **泛型封装**：支持所有Android权限类型
- 📊 **使用示例**：
```kotlin
@HiltAndroidApp
class TaiXuApplication : Application() {
    @Inject lateinit var permissionManager: PermissionManager
}

// 在ViewModel中
val cameraGranted = permissionManager.isGranted(Manifest.permission.CAMERA)
permissionManager.requestPermission(activity, Manifest.permission.CAMERA)
```

### 2. `harness/util/TokenBucket.kt` (264行)
**功能**：令牌桶限流器 + 递归深度保护
- ⚡ **限流算法**：每秒10 tokens，突发容量20
- 🔄 **递归保护**：最大5层调用深度，防止栈溢出
- 📈 **协程友好**：suspend函数集成，非阻塞等待
- 🎯 **使用场景**：子代理API调用、MCP消息频率控制
- 📊 **使用示例**：
```kotlin
val limiter = TokenBucket(ratePerSec = 10, burstCapacity = 20)
val recursionGuard = RecursionGuard(maxDepth = 5)

// 限流调用
limiter.withToken {
    callRemoteAPI()
}

// 递归保护
recursionGuard.execute(depth = currentDepth) {
    processSubAgent()
}
```

### 3. `runtime/shell/TimeoutShellExecutor.kt` (242行)
**功能**：带超时熔断的Shell命令执行器
- ⏱️ **超时控制**：默认30秒，可配置
- 🔪 **进程终止**：超时自动kill进程树
- 📝 **结构化输出**：分离stdout/stderr，记录执行时间
- 🛡️ **安全增强**：命令白名单校验（可选）
- 📊 **使用示例**：
```kotlin
val executor = TimeoutShellExecutor(timeoutSeconds = 30)

try {
    val result = executor.execute("ls -la /data/local/tmp")
    Log.d("Shell", "Exit: ${result.exitCode}, Time: ${result.durationMs}ms")
} catch (e: ShellTimeoutException) {
    Log.e("Shell", "命令执行超时，已强制终止")
}
```

### 4. `runtime/filesystem/TmpDirCleaner.kt` (281行)
**功能**：临时目录LRU清理服务
- 🗓️ **时间策略**：保留最近7天文件
- 💾 **容量策略**：上限500MB，超出时删除最旧文件
- 🛡️ **白名单保护**：关键目录永不删除
- ⏰ **定时任务**：每日凌晨2点自动执行
- 📊 **使用示例**：
```kotlin
val cleaner = TmpDirCleaner(
    context = application,
    maxAgeDays = 7,
    maxSizeBytes = 500 * 1024 * 1024
)

// 手动触发清理
val freedSpace = cleaner.clean()
Log.d("TmpCleaner", "释放空间: ${freedSpace / 1024 / 1024}MB")

// 注册定时任务（WorkManager）
cleaner.scheduleDailyCleanup(hour = 2)
```

### 5. `core/network/StructuredLoggingInterceptor.kt` (225行)
**功能**：OkHttp结构化日志拦截器
- 📄 **JSON格式**：机器可读，便于ELK收集
- 🔒 **自动脱敏**：隐藏Authorization、Cookie等敏感字段
- ⏱️ **性能监控**：记录请求耗时、响应大小
- 🎛️ **级别控制**：DEBUG/BODY/HEADERS多级日志
- 📊 **使用示例**：
```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(StructuredLoggingInterceptor(
        level = LoggingLevel.BODY,
        logger = { json -> Log.d("HTTP", json) }
    ))
    .build()

// 输出示例：
// {"timestamp":"2024-01-15T10:30:00Z","method":"GET","url":"https://api.taixu.dev/agents","durationMs":156,"requestHeaders":{"User-Agent":"TaiXu/1.0"},"responseCode":200,"responseBytes":2048}
```

---

## 📁 文件变更清单

| 文件路径 | 行数 | 类型 | 描述 |
|---------|------|------|------|
| `app/src/main/java/dev/taixu/app/permission/PermissionManager.kt` | +332 | 新增 | 统一权限管理器 |
| `harness/src/main/java/dev/taixu/harness/util/TokenBucket.kt` | +264 | 新增 | 令牌桶限流器 |
| `runtime/src/main/java/dev/taixu/runtime/shell/TimeoutShellExecutor.kt` | +242 | 新增 | Shell超时执行器 |
| `runtime/src/main/java/dev/taixu/runtime/filesystem/TmpDirCleaner.kt` | +281 | 新增 | 临时目录清理器 |
| `core/src/main/java/dev/taixu/core/network/StructuredLoggingInterceptor.kt` | +225 | 新增 | 结构化日志拦截器 |
| `.github/PULL_REQUEST_TEMPLATES/architecture_improvements.md` | +325 | 新增 | PR模板文档 |
| `IMPLEMENTATION_PROGRESS.md` | +240 | 新增 | 实施进度报告 |
| **总计** | **+1909** | | |

---

## 🧪 测试建议

### 单元测试
```bash
# 运行新增组件的单元测试
./gradlew :app:testDebugUnitTest
./gradlew :harness:testDebugUnitTest
./gradlew :runtime:testDebugUnitTest
./gradlew :core:testDebugUnitTest
```

### 集成测试场景
1. **权限管理**：模拟权限拒绝→跳转设置→返回授权→验证回调
2. **限流器**：高频调用API→验证令牌消耗→等待恢复→再次调用
3. **Shell超时**：执行sleep 60→验证30秒后终止→检查进程清理
4. **临时清理**：创建大量临时文件→触发清理→验证保留策略
5. **网络日志**：发送含Auth头的请求→验证脱敏→检查JSON格式

---

## 🔗 关联Issue
- Closes #123: 权限管理分散问题
- Closes #124: Agent资源泄漏风险
- Closes #125: Shell命令无限期阻塞
- Closes #126: 临时文件堆积占用存储
- Closes #127: 网络日志难以分析

---

## 📝 后续计划（未实现部分）

本PR实现了21项改进建议中的5项，剩余16项将在后续PR中逐步推进：

### 待实现功能
- [ ] **导航状态持久化** (feature/navigation)
- [ ] **动态路由注册** (feature/navigation)
- [ ] **数据库迁移测试** (core/database)
- [ ] **容器生命周期管理** (runtime)
- [ ] **文件系统FUSE层** (runtime)
- [ ] **MCP连接自愈** (harness)
- [ ] **工具安装事务原子性** (tools)
- [ ] **Compose性能优化** (feature UI)
- [ ] **无障碍支持** (feature UI)
- [ ] **主题系统扩展** (feature UI)
- ... 及其他6项

详细路线图请参阅 `IMPLEMENTATION_PROGRESS.md`

---

## ✅ 检查清单

- [x] 代码遵循项目Kotlin规范
- [x] 所有公开API包含KDoc文档
- [x] 提供完整使用示例
- [x] Hilt DI正确集成
- [x] Coroutines异步处理
- [x] 无破坏性变更（向后兼容）
- [x] 敏感信息脱敏处理
- [x] 异常场景妥善处理
- [ ] 单元测试覆盖（建议补充）
- [ ] 集成测试验证（建议补充）

---

## 👥 评审重点

请重点关注：
1. **权限管理器**的状态流转是否正确
2. **限流器**的令牌恢复算法是否准确
3. **Shell执行器**的进程树终止是否彻底
4. **临时清理器**的白名单是否完整
5. **日志拦截器**的脱敏规则是否充分

---

**合并建议**：Squash and Merge（保持提交历史简洁）

**风险评估**：低风险（新增组件，不影响现有逻辑）

**回滚方案**：直接Revert此PR即可，无数据迁移依赖
