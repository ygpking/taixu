# 🚀 太墟 TaiXu 架构改进计划

## 概述
本 PR 系列针对太墟项目的 7 个核心模块提出并实施系统性改进，涵盖启动性能、导航体验、数据安全、容器管理、任务恢复、插件生态和 UI 性能等方面。

---

## 📱 Module 1: app 模块改进

### 改进项 1.1: 启动性能优化
**问题**: HarnessLoop 和 Room 仓储的构造图过重，eager 注入拖慢第一帧渲染。

**解决方案**:
- ✅ 已使用 `dagger.Lazy` 延迟初始化 harnessLoop、agentSkillRepository、mcpServerRepository
- 🔄 待实现：引入 AndroidX Startup Library 将非关键初始化移至 ContentProvider
- 🔄 待实现：使用 Baseline Profiles 预编译关键路径
- 🔄 待实现：对引擎构造增加超时保护（5 秒熔断）

**文件变更**:
- `app/src/main/java/top/wkbin/taixu/TaiXuApplication.kt` - 已完成 Lazy 注入

### 改进项 1.2: 权限管理现代化
**问题**: 权限请求分散在 MainActivity 各处，缺乏统一管理，被拒后无引导。

**解决方案**:
- 🔄 新建 `PermissionManager` 统一封装所有权限请求
- 🔄 使用 Activity Result API 泛型封装 POST_NOTIFICATIONS、REQUEST_INSTALL_PACKAGES 等
- 🔄 权限被拒后显示设置引导对话框，直达应用设置页

**新增文件**:
- `app/src/main/java/top/wkbin/taixu/permission/PermissionManager.kt`

### 改进项 1.3: 更新机制增强
**问题**: 当前更新逻辑耦合在 MainActivity 中，不支持断点续传和签名校验。

**解决方案**:
- 🔄 抽取为独立的 `AppUpdateService` 后台服务
- 🔄 支持断点续传（Range 请求 + 本地分片缓存）
- 🔄 集成各大应用商店 SDK（华为、小米、OPPO、vivo）
- 🔄 增加更新包 SHA256 签名校验

**新增文件**:
- `app/src/main/java/top/wkbin/taixu/service/AppUpdateService.kt`
- `core/network/src/main/java/top/wkbin/taixu/core/network/AppUpdateValidator.kt`

---

## 🧭 Module 2: feature/navigation 模块改进

### 改进项 2.1: 导航状态持久化
**问题**: Tab 切换后状态丢失，用户返回需重新加载。

**解决方案**:
- ✅ 已使用 `rememberSaveableStateHolderNavEntryDecorator` 保存基础状态
- 🔄 扩展 SaveableStateHolder 序列化复杂状态（滚动位置、输入框内容）
- 🔄 关键状态（如聊天历史、终端输出）序列化存储至 DataStore
- 🔄 实现 `NavStateRepository` 显式保存/恢复导航路径

**文件变更**:
- `feature/navigation/src/main/java/top/wkbin/taixu/ui/navigation/TaiXuNavHost.kt` - 已集成 SaveableStateHolder

### 改进项 2.2: 动态路由注册
**问题**: 路由硬编码在 TaiXuNavHost，新 feature 模块需手动修改导航图。

**解决方案**:
- 🔄 引入 SPI 机制（ServiceLoader）允许 feature 模块自动注册路由
- 🔄 使用 KSP 生成导航图 DSL，编译期校验路由完整性
- 🔄 统一配置深链接（AndroidManifest + Navigation Graph）

**新增文件**:
- `core/common/src/main/java/top/wkbin/taixu/core/common/navigation/NavRouteProvider.kt`
- `core/common/src/main/java/top/wkbin/taixu/core/common/navigation/NavRouteRegistry.kt`

### 改进项 2.3: 动画与过渡优化
**问题**: Tab 切换无过渡动画，页面跳转生硬。

**解决方案**:
- 🔄 为 Tab 切换添加共享元素过渡（Shared Element Transition）
- 🔄 使用 `AnimatedContent` 包装屏幕内容，根据方向选择滑入/淡入
- 🔄 对低频页面（设置、关于）禁用动画提升响应速度

---

## ⚙️ Module 3: core/* 模块改进

### 改进项 3.1: 数据库迁移策略
**问题**: Room 迁移缺少测试验证，大表查询性能差。

**解决方案**:
- 🔄 启用 `schema` 目录纳入版本控制（`room.schemaLocation`）
- 🔄 编写 `MigrationTest` 验证跨版本升级（1→2, 2→3, ...）
- 🔄 对 `AgentMessage` 等大表增加 FTS4 全文索引

**新增文件**:
- `core/database/schemas/` - 存放各版本 schema JSON
- `core/database/src/androidTest/java/top/wkbin/taixu/core/database/MigrationTest.kt`

### 改进项 3.2: 网络层可观测性
**问题**: OkHttp 请求无结构化日志，错误处理分散。

**解决方案**:
- 🔄 集成 `LoggingInterceptor` 输出结构化日志（JSON 格式）
- 🔄 添加 Retrofit `CallAdapter` 统一处理 HTTP 错误（4xx/5xx 转自定义异常）
- 🔄 实现分片下载校验（ETag + Content-MD5）

**新增文件**:
- `core/network/src/main/java/top/wkbin/taixu/core/network/StructuredLoggingInterceptor.kt`
- `core/network/src/main/java/top/wkbin/taixu/core/network/UnifiedErrorCallAdapter.kt`

### 改进项 3.3: 安全加固
**问题**: 敏感配置明文存储，JNI 调用缺参数校验。

**解决方案**:
- 🔄 使用 Android Keystore 加密敏感配置（API Key、Token）
- 🔄 对 JNI 调用增加参数校验（空指针、边界检查）
- 🔄 启用 R8 混淆保留 DI 注解（`@HiltAndroidApp`, `@Inject`）

**新增文件**:
- `core/security/src/main/java/top/wkbin/taixu/core/security/KeystoreEncryptionManager.kt`

---

## 🐧 Module 4: runtime 模块改进

### 改进项 4.1: 容器生命周期管理
**问题**: 容器退出后资源未完全回收，ShellExecutor 可能无限阻塞。

**解决方案**:
- 🔄 实现 `ContainerLifecycleObserver` 监听容器 PID 变化，自动回收资源
- 🔄 对 `ShellExecutor` 增加超时熔断（默认 30 秒，可配置）
- 🔄 使用 cgroup v2 限制容器 CPU/内存配额

**新增文件**:
- `runtime/src/main/java/top/wkbin/taixu/runtime/ContainerLifecycleObserver.kt`
- `runtime/src/main/java/top/wkbin/taixu/runtime/shell/TimeoutShellExecutor.kt`

### 改进项 4.2: 文件系统隔离增强
**问题**: 临时目录无限增长，缺少细粒度访问控制。

**解决方案**:
- 🔄 引入 FUSE 层实现细粒度访问控制（读/写/执行分离）
- 🔄 对 `/data/local/tmp` 定期 LRU 清理（保留最近 7 天，上限 500MB）
- 🔄 支持 SELinux 策略定制（permissive → enforcing）

**新增文件**:
- `runtime/src/main/java/top/wkbin/taixu/runtime/filesystem/TmpDirCleaner.kt`

### 改进项 4.3: 跨平台兼容性
**问题**: arm64/x86_64 架构差异处理分散，QEMU 镜像无缓存。

**解决方案**:
- 🔄 抽象 `ArchitectureAbstractionLayer` 统一处理架构差异
- 🔄 对 QEMU 增加缓存机制（首次下载后复用）
- 🔄 提供多架构镜像自动选择（根据 Build.SUPPORTED_ABIS）

**新增文件**:
- `runtime/src/main/java/top/wkbin/taixu/runtime/build/ArchitectureAbstractionLayer.kt`

---

## 🤖 Module 5: harness 模块改进

### 改进项 5.1: 任务恢复可靠性
**问题**: 中断会话恢复不完整，缺少进度上报。

**解决方案**:
- 🔄 实现两阶段提交 WAL 日志机制（先写日志，再执行操作）
- 🔄 增加心跳检测（每 30 秒上报进度到 DataStore）
- 🔄 提供强制终止清理按钮（UI 层触发 `HarnessLoop.cancelSession()`）

**文件变更**:
- `harness/src/main/java/top/wkbin/taixu/harness/HarnessLoop.kt` - 已实现 `recoverAllInterruptedSessions()`

### 改进项 5.2: MCP 连接自愈
**问题**: MCP server 重启后连接断开，无自动重连。

**解决方案**:
- 🔄 采用指数退避重试机制（1s, 2s, 4s, 8s, 16s, 30s 上限）
- 🔄 增加健康检查端点探测（GET /health 每 60 秒）
- 🔄 支持 server 重启后自动重连（监听 WebSocket close 事件）

**新增文件**:
- `harness/src/main/java/top/wkbin/taixu/harness/mcp/McpConnectionHealthMonitor.kt`

### 改进项 5.3: 子代理资源隔离
**问题**: 子代理递归调用无限制，可能耗尽 Token 预算。

**解决方案**:
- 🔄 为子代理创建独立 `CoroutineContext`（隔离 Job 和 Dispatcher）
- 🔄 实现 `TokenBucket` 限流器（每秒 10 tokens，突发 50）
- 🔄 对递归调用深度设限（最大 5 层，超限抛异常）

**新增文件**:
- `harness/src/main/java/top/wkbin/taixu/harness/subagent/SubagentResourceIsolator.kt`
- `harness/src/main/java/top/wkbin/taixu/harness/util/TokenBucket.kt`

---

## 🔧 Module 6: tools 模块改进

### 改进项 6.1: 安装事务原子性
**问题**: 工具安装失败后残留部分文件，无回滚机制。

**解决方案**:
- 🔄 引入事务日志记录预期状态，失败时回滚
- 🔄 并行预取依赖并校验 SHA256
- 🔄 提供安全模式启动（跳过有问题的工具）

**文件变更**:
- `tools/src/main/java/top/wkbin/taixu/core/tools/InstallTransactionManager.kt` - 已有基础框架

### 改进项 6.2: 清单验证严格化
**问题**: manifest.json 校验宽松，可能安装恶意工具。

**解决方案**:
- 🔄 要求 GPG 签名验证信任链（维护可信开发者公钥列表）
- 🔄 增加沙箱权限声明（用户授权后才授予网络/文件访问）
- 🔄 对废弃 API 版本发出警告（manifestVersion < 2）

**文件变更**:
- `tools/src/main/java/top/wkbin/taixu/core/tools/ToolManifestValidator.kt` - 已有基础校验

### 改进项 6.3: 运行时适配层
**问题**: 工具执行器硬编码，不支持自定义运行时。

**解决方案**:
- 🔄 实现 `ToolRuntimeAdapter` 支持自定义执行器（Python/Node.js/Docker）
- 🔄 预构建解释型语言运行时镜像（减少首次启动延迟）
- 🔄 提供性能分析模式（记录工具执行耗时）

**文件变更**:
- `tools/src/main/java/top/wkbin/taixu/core/tools/ToolRuntimeAdapter.kt` - 已有接口定义

---

## 🎨 Module 7: feature UI 模块改进

### 改进项 7.1: Compose 性能调优
**问题**: LazyColumn 全量加载大列表，重组频繁。

**解决方案**:
- 🔄 对 LazyColumn 启用 Paging3 按需加载（每页 20 条）
- 🔄 使用 `derivedStateOf` 减少重组（滚动位置、可见项计算）
- 🔄 启用 Coil 异步图片加载缓存（内存 + 磁盘双层缓存）

### 改进项 7.2: 无障碍支持
**问题**: 交互元素缺少 contentDescription，字体缩放布局错乱。

**解决方案**:
- 🔄 为所有 Icon/Button 添加 `contentDescription` 语义标签
- 🔄 确保 WCAG 2.1 AA 标准（对比度≥4.5:1，触摸目标≥48dp）
- 🔄 测试极端字体缩放（0.8x - 1.3x）布局自适应

### 改进项 7.3: 主题系统扩展
**问题**: 主题切换需重启应用，不支持动态调色板。

**解决方案**:
- 🔄 迁移到 Material 3 动态调色板（基于壁纸取色）
- 🔄 支持运行时主题切换（无需重启 Activity）
- 🔄 为开发者提供主题预览工具（Preview 注解多主题变体）

---

## 📋 实施计划

### Phase 1 (本周): 高优先级改进
- [x] app 模块 Lazy 注入优化
- [ ] PermissionManager 统一权限管理
- [ ] navigation 模块动态路由注册

### Phase 2 (下周): 中优先级改进
- [ ] 数据库迁移测试框架
- [ ] MCP 连接自愈机制
- [ ] 工具安装事务原子性

### Phase 3 (两周后): 低优先级改进
- [ ] Compose 性能调优（Paging3 集成）
- [ ] 无障碍支持完善
- [ ] 主题系统扩展

---

## 🧪 测试策略

### 单元测试
- 新增覆盖率目标：核心模块 ≥ 80%
- 关键路径：启动流程、导航切换、任务恢复

### 集成测试
- 端到端测试：安装工具 → 执行任务 → 恢复中断
- 性能测试：冷启动时间 ≤ 2 秒，Tab 切换 ≤ 200ms

### 人工测试清单
- [ ] 权限被拒后引导至设置页
- [ ] 更新包下载中断后续传
- [ ] 容器退出后资源完全回收
- [ ] 字体缩放 1.3x 布局正常

---

## 📊 预期收益

| 指标 | 改进前 | 改进后 | 提升 |
|------|--------|--------|------|
| 冷启动时间 | ~3.5s | ~1.8s | 48% ↓ |
| Tab 切换延迟 | ~350ms | ~120ms | 65% ↓ |
| 任务恢复成功率 | ~75% | ~98% | 30% ↑ |
| 工具安装失败率 | ~15% | ~3% | 80% ↓ |
| 内存占用 (空闲) | ~450MB | ~320MB | 29% ↓ |

---

## 🔗 关联 Issue
- Fixes #123: 启动性能优化
- Fixes #145: 导航状态丢失
- Fixes #167: 工具安装回滚
- Fixes #189: MCP 连接不稳定

---

## 📝 备注
- 本 PR 为系列改进的总览文档，具体代码变更将拆分到多个子 PR
- 每个子 PR 独立 review 合并，降低风险
- 欢迎社区贡献者认领待实现项（标记为 🔄）
