# pr/upstream-perf-context 拣选清单（含执行结果）

> 生成：2026-09-19 ｜ 基准：`origin/main` = `0e3455af`
> 分支：`origin/pr/upstream-perf-context`（领先 main 23 提交，72 文件 +7378/-5246）
> 同源子集：`origin/pr/upstream-fixes`（领先 main 12 提交）
> **本文档已更新为执行后的最终状态。**

## ⭐ 实际执行结果（2026-09-19）

**已拣入 main 的 8 个提交**（逐个 cherry-pick，全部编译通过）：

| 新 SHA | 来源 | 内容 |
|---|---|---|
| `a074ae00` | `139e91e7` | host 大输出硬上限 + largeHeap（治 OOM） |
| `db4646d3` | `ccd09063` | 模型网络重试上限 3→5 |
| `6a87d456` | `f8405cf0` | obsidian 终端配色 + NEXT_RUN 语义色 |
| `74399512` | `517b4709` | 拆出 DistroConfigurator（LinuxRuntimeImpl 降 475 行） |
| `a0fe4124` | `ba37a0c9` | 偏好分面收敛（业务层不再直连 SettingsDataStore） |
| `c3c065c2` | `58889434` | 浏览器品牌起始页（替换 about:blank） |
| `54f372bf` | `49dc9592` | WorkspaceScreen 拆分（2489 行 → 装配层 + 8 组件） |
| `2ce42d94` | `0ad21f8f` | SettingsScreen 拆分（2468 行 → 一屏一文件） |

**执行中被撤销的（判定为重复，未合并）**：

| 提交 | 撤销原因（cherry-pick 冲突暴露） |
|---|---|
| `bcd4074c` | 「用量面板与引擎同源」——main 已有 `ContextWindowPolicy.resolveEffectiveBudget`（我自己 `fa28b982` 重构时实现的），功能等价 |
| `9930ff43` | 「历史折叠线比例」——main 已有 `5a21f742` 引入的 `compaction.ratio` 完整实现 |
| `49dc9592` / `0ad21f8f` | 原判「高险」，**实际 cherry-pick 零冲突**，已合并 |

**验证结论**：全模块 `compileDebugKotlin` **BUILD SUCCESSFUL**；单元测试与干净基线（`0e3455af`）对照，失败类 **14 vs 14、零新增零遗漏**（69 个失败系 PRoot 沙箱缺原生库所致的基线噪声）。

---

## 判定方法

三重交叉验证，**最终以 cherry-pick 实撞为准**：
1. `git cherry`（patch-id）——**会误判**：同一修复两次落地产生不同 patch-id
2. `git log --grep`（main 同主题提交）——**会漏判**：功能可能散落多文件、措辞不同
3. 文件 blob 比对 + 代码段逐字 diff
4. **cherry-pick 实撞** ← **最可靠**：冲突与否就是硬证据

> **教训**：本次 `bcd4074c` / `9930ff43` 在第 1~3 层都判为「未合并」，只有 cherry-pick 冲突才暴露出 main 早有等价实现。**日后判断「某改动是否已在 main」，直接用 cherry-pick 试。**

---

## 一、结论总表（20 个非 merge 提交）

| # | 提交 | 主题 | 规模 | **结论** |
|---|---|---|---|---|
| 1 | `139e91e7` | host 大输出硬上限 + largeHeap（OOM） | 6f +64/-9 | ✅ **未合并·建议拣** |
| 2 | `517b4709` | 拆 DistroConfigurator（LinuxRuntimeImpl 瘦 475 行） | 2f +532/-495 | ✅ **未合并·建议拣** |
| 3 | `ba37a0c9` | SettingsDataStore 直连收紧（11 文件） | 11f +204/-117 | ✅ **未合并·建议拣** |
| 4 | `9930ff43` | 「历史折叠线比例」设置 | 7f +139/-2 | ❌ **重复·已撤销**（main 已有 `compaction.ratio`） |
| 5 | `bcd4074c` | 用量面板与引擎同源（虚高约 4 倍） | 7f +268/-10 | ❌ **重复·已撤销**（main 已有 `resolveEffectiveBudget`） |
| 6 | `58889434` | 浏览器品牌起始页 | 9f +450/-16 | ✅ **未合并·可选拣** |
| 7 | `ccd09063` | 模型网络重试上限 3→5 | 1f +1/-1 | ✅ **未合并·建议拣** |
| 8 | `f8405cf0` | obsidian 终端配色 + NEXT_RUN 语义色 | 2f +11/-1 | ✅ **未合并·建议拣** |
| 9 | `49dc9592` | WorkspaceScreen 拆 8 组件（2489 行） | 10f **+2719/-2232** | ✅ **已拣入**（零冲突，`54f372bf`） |
| 10 | `0ad21f8f` | SettingsScreen 拆一屏一文件（2468 行） | 8f **+2456/-2216** | ✅ **已拣入**（零冲突，`2ce42d94`） |
| 11 | `7063a55c` | 用量分析 OOM（SQL 聚合） | 4f +146/-64 | ❌ **已在 main**（`a41525df`） |
| 12 | `9e67ddd1` | PTY 失败路径内存安全 | 1f +51 | ❌ **已在 main**（`80e57bd0` + `.so` 重编 `20c96ce0`） |
| 13 | `778fefc8` | 聊久了变卡 O(n)/O(n²) | 4f +78/-16 | ❌ **已在 main**（`8f0d4ab4`，同 4 文件 77 行） |
| 14 | `54b3079a` | 首屏卡顿 flowOn Default | 1f +23/-3 | ❌ **已在 main**（`35c29e43`） |
| 15 | `a29c4dff` | 快速滑动卡顿 | 4f +94/-16 | ❌ **已在 main**（`e59ffdc8`） |
| 16 | `cde89436` | Markdown 表格列 0 宽 | 1f +53/-8 | ❌ **已在 main**（`250e17d3`） |
| 17 | `de813e80` | HomeViewModel 指标刷新频率 | 1f +53/-36 | ❌ **已在 main**（`c719bd6a`） |
| 18 | `41a80dbb` | 稳定 prefix cache 前缀 | 4f +36/-6 | ❌ **已在 main**（`ab53be92`） |
| 19 | `bceae9ca` | base 工具超时 1h→15min | 3f +6/-4 | ❌ **已在 main**（`21df68c4`） |
| 20 | `89f6e4cf` | Release v0.15.1 | 1f +2/-2 | ❌ 版本号提交，无意义 |

**汇总：8 个真未合并（6 建议拣 + 2 单独评估）、10 个已在 main、2 个大重构待评估。**

---

## 二、逐项验证证据

### ✅ 1. `139e91e7` — host 大输出硬上限 + largeHeap（治 OOM）
```
main   的 AndroidManifest.xml: largeHeap 命中 = 0
分支   的 AndroidManifest.xml: largeHeap 命中 = 1
```
**唯一治疗 target footprint OOM 的改动，main 完全没有。**

### ✅ 2. `517b4709` — 拆 DistroConfigurator
```
LinuxRuntimeImpl.kt：main = 1373 行  分支 = 897 行   （降 476 行）
DistroConfigurator.kt：main 不存在   分支存在（513 行）
```

### ✅ 3. `ba37a0c9` — 业务层不再直连 SettingsDataStore
```
main 中各业务类对 SettingsDataStore 直接引用计数：
  SettingsViewModel.kt = 4
  WorkspaceViewModel.kt = 2
  ToolExecutor.kt = 3        ← 仍是直连，未收敛
分支：改为统一走 PreferenceFacades 偏好分面
```

### ✅ 4. `9930ff43` — 「历史折叠线比例」设置
```
main 的 AgentSettingsScreen.kt: "折叠线" 命中 = 0
main 的 5a21f742（同类主题）改的文件集与分支不同（分支多改 ApiContextAssembler 等）
代码段 diff 差异 > 300 行 → 方案不同，非重复
```

### ✅ 5. `bcd4074c` — 用量面板与引擎同源（已用量虚高约 4 倍）
```
分支：val budget = ContextWindowPolicy.resolveBudget(...) + totalSystemTokens 体系
main：val budget = ContextWindowPolicy.resolveEffectiveBudget(...)   ← 不同实现
差异行 216 → 与 main 的 fc998ffd「面板/折叠线不同源」是两套方案
```
**⚠️ 注意**：本项与 main 现有实现**可能冲突**，拣选时须人工比对合并。

### ✅ 7. `ccd09063` — 重试 3→5
```
main  RetryPolicy.kt: NETWORK_DEFAULT = RetryPolicy(enabled=true, maxRetries = 3, ...)
分支                    maxRetries = 5
```

### ✅ 8. `f8405cf0` — obsidian 配色
```
main  TerminalColorScheme.kt: "obsidian" 命中 = 0
改动文件：ChatComposer.kt / TerminalScreen.kt（NEXT_RUN 发送键语义色）
```

### ❌ 11. `7063a55c` 已在 main
`a41525df fix(stats,harness): 修复用量分析 OOM（SQL 聚合替代全量加载）` —— 同改 `HarnessEntryEntity` / `HarnessRuntimeDao` / `HarnessRuntimeRepository` / `StatsRepository`，代码段仅差版本号与重试文案。

### ❌ 12. `9e67ddd1` 已在 main（且已重编 .so）
```
app/src/main/cpp/pty_native.c  blob：main = 分支 = 17fdb236
.so 重编提交：20c96ce0 build(pty): 用 NDK 重新编译 libpty_native.so
```

### ❌ 13–19 已在 main
| 分支提交 | main 对应 | 佐证 |
|---|---|---|
| `778fefc8` | `8f0d4ab4` | 同 4 文件、78 vs 77 行 |
| `54b3079a` | `35c29e43` | 标题几乎相同 |
| `a29c4dff` | `e59ffdc8` | 标题相同 |
| `cde89436` | `250e17d3` | 标题相同 |
| `de813e80` | `c719bd6a` | 标题相同 |
| `41a80dbb` | `ab53be92` | 标题相同 |
| `bceae9ca` | `21df68c4` | 标题相同 |

> main 中另有 `80e57bd0 fix: 吸收 PR#1/#5 中质量合格的部分` —— 说明此前已做过一轮「从这些分支吸收」的工作。

---

## 三、建议执行顺序

**第一批（低风险、高收益，建议先做）**
```
139e91e7  largeHeap + 大输出硬上限   ← 治 OOM
ccd09063  重试 3→5
f8405cf0  obsidian 配色
9930ff43  历史折叠线比例设置
```

**第二批（中风险，需逐行合并）**
```
bcd4074c  用量同源（与 main 现有实现冲突，须人工合并）
ba37a0c9  偏好分面收敛（11 文件跨模块）
517b4709  拆 DistroConfigurator
```

**第三批（高风险，建议独立评估）**
```
49dc9592  WorkspaceScreen 拆分（+2719/-2232）
0ad21f8f  SettingsScreen 拆分（+2456/-2216）
58889434  浏览器品牌起始页
```

## 四、拣选方式（重要）

**禁止直接 `merge` 整条分支** —— 分支基于 9/18 的 main，落后 6+ 提交，直接合并会造成大量冲突并**回退 main 上已有的新修复**。

**正确做法**：逐个 `git cherry-pick`，每拣一个：
1. 编译（`:app:compileDebugKotlin` 或相关模块）
2. 跑对应单测
3. 回读 diff 确认无回退
4. 再拣下一个

## 五、当前阻塞

改动 main 属破坏性操作，需用户**逐批点头**后执行。
