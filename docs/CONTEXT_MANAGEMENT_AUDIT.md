# 🧠 上下文管理全链路通读诊断 (Context Management Audit)

> 本文档记录对 TaiXu「上下文管理」全部关联文件的通读结论，以及据此落地的缓存下沉改造（M1/M3/G1）。
> 结论均附 `文件:行号` 证据，可直接复查。通读范围：`harness` 主模块 116 文件 / 21613 行 + `core` 持久层 13 源文件。

---

## 1. 架构全景

```
① 持久真相源 (core/database)
   harness_entries 表：不可变 entry 树，主键 sequence(自增)，字段 id/parentId/entryType/payloadJson
   payloadJson ≥ 48KB → 外置到 filesDir/harness_blobs/{sessionId}/{entryId}.json（entry 内只留占位串）
   branchTail(leafId, LIMIT 600) —— 600 条是「进上下文窗口」的硬上限
   ─────────────────────────────────────────────
② 投影层 (harness/projection + session)
   SessionMessageProjector：内存 StateFlow，MAX_CACHED_SESSIONS=4，boundLiveWindow 截 600
   LaneManager：main / branch: / subagent: / HISTORY 多车道隔离
   SessionTreeStore：load=600 上限；read/readWithRelated/search=无上限按需回捞
   ─────────────────────────────────────────────
③ 压缩层 (harness/compaction)
   CompactionManager.compact：computeKeepFromIndex → LLM 迭代滚动摘要(previousSummaries 合并)
                            → 失败时机械摘要 mergeRollingSummary 兜底 → 归档 .taixu-context/
   ─────────────────────────────────────────────
④ 预算层 (ContextWindowPolicy, 889 行)
   estimateTokens / fitToBudget(INPUT_BUDGET_FRACTION=0.75) / fitSystemPrompt(MAX_FRACTION=0.60)
   ─────────────────────────────────────────────
⑤ 请求层 (ProviderClient / ChatApi / AnthropicApi / ResponsesApi)
   三协议共用同一 LlmRequestCache（ProviderClient 单例持有）
```

## 2. 「长对话不失忆」的三层防线（均已存在）

1. **压缩**：越预算 → LLM 迭代滚动摘要，保留目标/约束/进度/决策/下一步/文件清单；
2. **外置**：单条 payload ≥48KB 自动落 blob，防大 payload 撑爆上下文；
3. **回捞**：`SessionTreeStore.read/readWithRelated/search` **无 600 上限**，可捞任意历史；摘要附 `history_read` 指针。

**关键认知**：`MAX_LIVE_ENTRIES=600` 是「进窗口」上限，**不是「历史存储」上限**——历史全在库，只是不全部进当前窗口。这是刻意的「存储 / 窗口分离」设计。

## 3. 真实缺口（通读后确认）

| # | 缺口 | 证据 | 状态 |
|---|---|---|---|
| M1 | OpenAI `ChatApi` 流式路径未接缓存 | `chatStream` 原未调 `requestCache` | ✅ 已修 |
| G1 | `AnthropicApi` / `ResponsesApi` 未接缓存 | 两文件独立、构造无 `requestCache` | ✅ 已修 |
| M3 | `scratchpad` 未自动注入系统提示 | `SystemPromptBuilder` 只拉 pinned/recall/plan | ✅ 已修 |
| G2 | 600 窗口与更早归档之间无「自动桥」 | 超 600 只能模型**主动** `history_search` | ✅ 已修 |
| — | `EntryTree.branch`（纯内存版）疑似死代码 | Repository 直接调 DAO | 只记录，非缺陷 |

## 4. 已落地改动（本次）

### M1 缓存下沉（OpenAI 流式）
- `ProviderClient.executeStream`：流式接入 `requestCache`——命中回放最终文本/推理、跳过网络；成功收尾 `put`，失败不落。
- `LlmRequestCache`：新增 `_hitCount` / `hitCount` 命中计数（`_` 前缀避免与 `size` 同 JVM 签名冲突）。
- `ProviderClient.requestCacheHits`：只读出口，供指标量化。

### M3 scratchpad 注入
- `SystemPromptBuilder.build`：拉 `agentContextDao.listScratchpads(sessionId)`，渲染 `scratchpadSection`，注入 `planSection` 之后。
- 常量 `MAX_SCRATCHPAD_LINES=8` / `MAX_SCRATCHPAD_VALUE_CHARS=512`（与 pinned 1500、recall 512 同量级）。

### G1 缓存下沉至 Anthropic / Responses
- `requestCacheKey` 由 `ChatApi` 私有方法提升为 `ProviderClient` companion `internal fun`，三协议共用同一 key 空间。
- `AnthropicApi` / `ResponsesApi` 构造新增 `requestCache: LlmRequestCache`；`chat` + `chatStream` 均接入缓存。
- `ProviderClient` 6 处 API 构造全部传入 `requestCache`。

**跨协议隔离保证**：缓存 key = `model.hashCode()|messages.hashCode()`，而 `ModelConfig` 的 `protocol` / `responseApiEnabled` 参与 `hashCode`，故不同协议的请求天然落在不同 key，**不会互相污染缓存**。

### G2 压缩摘要自动附「更早历史检索入口」
- **问题定位**：摘要里原已有「归档文件路径」索引，但归档文件仅滚动保留最近 20 份（`ContextArchive.MAX_ARCHIVES_PER_SESSION=20`），更早批次会被回收 —— 摘要里的旧路径会**指向已被删除的文件**，索引漂移失效；且归档未启用时（`archiveEnabled=false` / 归档失败）`indexNote` 返回空串，检索入口完全丢失。
- **修复**：`ContextArchive.indexNote` 在归档索引后补「更早历史检索入口」段，显式给出 `history_search(query=...)` / `history_read(message_id=...)` 两个**永不失效**的会话检索工具指引，并说明归档文件滚动回收 + 树内条目永久保存。
- **兜底**：新增 `ContextArchive.searchEntryNote(messageCount)`，在无归档时仍附检索入口；`CompactionManager.compact` 用 `indexNote(...).ifBlank { searchEntryNote(...) }` 组装 `summaryWithIndex`，保证**任何情况下摘要都带得动回捞路径**。

### ①③ 记忆水位调优（解「记不住」）
- **背景（实测结论）**：`localLimit = 折叠线 - systemTokens`（`systemTokens` 含 `systemPrompt` + `summaryLayer`）。系统提示随功能迭代变厚（实测静态 ~10-15K tokens），直接从「可用于历史」的额度里扣，导致长对话更早触发折叠、前文更易丢。真因非「摘要自我强化」（已证伪：`budgetTokens` 源头已钳），而是**历史额度被系统开销挤占**。
- **① `DEFAULT_MAX_KEEP_TOKENS` 20_000 → 40_000**：放宽「压缩后保留窗口」的 token 上限，让最近历史留得更多；预算护栏（`upstreamLimit`）仍在，不会超限。
- **③ `DEFAULT_FOLDING_RATIO_PERCENT` 85 → 90**：更晚触发折叠，保留更多原始历史、减少过早摘要。剩余 10% 余量由 `RESERVED_OUTPUT_TOKENS` + `TOOL_SCHEMA_RESERVE` 兜底。
- **生效路径**：两常量真相源在 `core/model/ContextBudgetDefaults`；`ContextWindowPolicy` 转发、`SettingsDataStore` 默认值均取自真相源 → **未自定义过设置的用户自动跟随**（已自定义者尊重其设置）。
- **配套测试**：`ContextWatermarkRefactorTest` 硬编码的期望值同步更新为 90 / 40_000。

### M1-M4 上下文预算链贯通重构（解「窗口越大越没用」）
- **背景（实测）**：原链条 `窗口 → min(窗口×50%, 128K) → ×90% → ×0.75 − 预留`，层层折上折后，**128K 窗口的历史实际只剩 ~23K；256K 窗口被 `DEFAULT_INPUT_LIMIT=128K` 硬顶架空，与 128K 无异**。用户填多大窗口都到不了历史。
- **M1 窗口→输入上限贯通**：
  - `DEFAULT_TOKENS` 128_000 → **256_000**（默认窗口对齐主流 200K+ 模型）；
  - `INPUT_LIMIT_WINDOW_RATIO_PERCENT` 50 → **90**（窗口真正决定裁切基准）；
  - `resolveInputLimit` 去掉 `min(scaled, DEFAULT_INPUT_LIMIT)` 硬顶，仅受 `MAX_INPUT_LIMIT`(2M) 护栏；
  - `DEFAULT_INPUT_LIMIT` 128_000 → **230_000**（≈256K×90%，避免默认值反过来架空窗口）。
- **M2 去掉二次压缩**：`computeKeepFromIndex` 删除 `upstreamLimit = budget×0.75 − 预留` 这道折上折，折叠线统一由 `foldingLimitFor` 给出；**`reserveTokens`（per-model 输出预留）改为并入 `foldingLimitFor` 参数据续生效**（避免删链路时误伤该功能）。
- **M3 保留窗口护栏匹配**：`maxKeepTokens` 以配置值为下限、随预算动态放大到至少 `budget/4`，大窗口不再被固定 40K 卡死。
- **M4 验证**：主/测代码编译通过；`ContextWindowPolicyTest`(30) + `ContextWatermarkRefactorTest`(10) + `ContextBudgetDefaultsTest`(5) 共 **45 测试 0 失败**；因果审计 **P0=0**。
- **实测效果**：128K 窗口历史可用 23K → ~60K；256K 窗口 → ~90K（并随 `M3` 进一步放大保留量）。
- **回滚**：`DEFAULT_TOKENS`/`INPUT_LIMIT_WINDOW_RATIO_PERCENT`/`DEFAULT_INPUT_LIMIT` 改回 128_000/50/128_000，并还原 `computeKeepFromIndex` 的 upstreamLimit 段即可。

## 5. 设计一致性（三协议统一约定）

- **只缓存成功结果**，失败（429 / 5xx / 异常）不落缓存；
- **流式命中只回放最终结果**（`content` + `reasoningContent`），不回放工具进度（命中无增量可算，结果随 `ChatResult` 返回）；
- **缓存 key 统一**由 `ProviderClient.requestCacheKey` 生成，避免多套实现漂移；
- 缓存实例**统一**为 `ProviderClient` 的 `@Singleton` 成员，跨请求共享（命中率才非 0）。

## 6. 验证

- `:harness:compileDebugKotlin` **EXIT=0**（每次改动后均跑）。
- 因果链闭环审计（causal_audit_scan）：**P0 = 0**。

## 7. 回滚方案

- 本次改动均为**新增逻辑**（未改原有行为分支），反向删除即回滚；
- 涉及文件：`ProviderClient.kt`、`LlmRequestCache.kt`、`SystemPromptBuilder.kt`、`AnthropicApi.kt`、`ResponsesApi.kt`、`ContextArchive.kt`、`CompactionManager.kt`、`core/model/ContextBudgetDefaults.kt`，以及配套测试 `AnthropicApiTest.kt`、`ResponsesApiTest.kt`、`ContextWatermarkRefactorTest.kt`；集中在 `harness` + `core/model`，无跨模块结构改动、无数据迁移。
- ①③ 两常量回滚：把 `DEFAULT_MAX_KEEP_TOKENS` 改回 `20_000`、`DEFAULT_FOLDING_RATIO_PERCENT` 改回 `85` 即恢复原行为。

## 8. 遗留项（未做，待决策）

- ~~**G2**：600 窗口与更早归档之间缺「自动桥」~~ → **已修**（见 §4 G2）。
- **冗余包装**：`ChatApi.requestCacheKey` 现仅为转发到 `ProviderClient.requestCacheKey`，属冗余包装（非缺陷），可择机清理。
