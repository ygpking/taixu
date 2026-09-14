#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ==============================================================================
# TaiXu (LinuxAIRuntime) - 因果链闭环审计 MCP 服务端 (stdio transport)
# ------------------------------------------------------------------------------
# 把「同一语义在多处实现/读取/显示不一致」这类结构性缺陷做成可机械验证的静态检查，
# 取代靠人肉模式搜索打地鼠的排查方式。
#
# 检测规则：
#   R1 单一名常量跨模块重名且取值不同（单一真相源缺失，可能语义漂移）
#   R2 对 StateFlow 调用 distinctUntilChanged（Kotlin 标注为 ERROR 级 deprecation → 编译失败）
#   R3 combine 输出被压缩为恒定值 + 下游去重 → 其余上游变化被吞（UI 不刷新）
#   R4 同一偏好键的默认值在多处不一致
#   R5 偏好键只写不读（僵尸设置：UI 可调，引擎不读）
#
# 原理澄清（避免误判）：
#   combine(a, b, c) { x, _, _ -> x } 中的 `_` 只是「不使用该参数」，
#   combine 本身任一上游发射都会重跑 lambda —— **丢参数不会阻止触发**。
#   真正的缺陷是「输出恒定为 x」再叠加 distinctUntilChanged：此时 x 未变，
#   其余上游的任何变化都被去重吞掉，相关 UI 只在 x 变化时才刷新。
#
# 协议：MCP stdio，逐行 JSON-RPC（与 McpManager.discoverStdioTools /
#       executeStdioTool 的 line-delimited JSON 交互方式对齐）。
# 依赖：仅 python3 标准库。
# ==============================================================================
import argparse
import json
import os
import re
import sys
from collections import defaultdict

PROTOCOL_VERSION = "2025-06-18"
MAX_OUTPUT_CHARS = 60000

SKIP_DIRS = {"build", ".git", ".gradle", ".idea", "node_modules", ".codegraph", ".kotlin"}


# ============================== 基础工具 ==============================

def response(req_id, result=None, error=None):
    value = {"jsonrpc": "2.0", "id": req_id}
    value["error" if error is not None else "result"] = error if error is not None else result
    return value


def truncate(text):
    if len(text) <= MAX_OUTPUT_CHARS:
        return text
    return text[:MAX_OUTPUT_CHARS] + "\n...[输出过长已截断]..."


def iter_sources(root, include_tests=True):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        if not include_tests:
            parts = dirpath.split(os.sep)
            if "test" in parts:
                continue
        for fn in filenames:
            if fn.endswith(".kt") or fn.endswith(".kts"):
                yield os.path.join(dirpath, fn)


def read(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return f.read()
    except Exception:
        return ""


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def ensure_workspace(path):
    root = os.path.abspath(path or "/workspace")
    if not os.path.isdir(root):
        raise RuntimeError("目录不存在: {}".format(root))
    return root


# ============================== 检测规则 ==============================

def rule_duplicate_constants(root, files):
    """R1 同名常量跨模块重名且值不同。同模块内的 private const 不报。"""
    findings = []
    pat = re.compile(
        r"^\s*(?:private\s+|internal\s+|public\s+)*const\s+val\s+([A-Z][A-Z0-9_]*)\s*[:=]\s*"
        r"(?:Int|Long|Float|Double)?\s*[=]?\s*([0-9][0-9_]*|[0-9_]*[0-9])\b",
        re.M,
    )
    by_name = defaultdict(list)
    for path in files:
        src = strip_comments(read(path))
        for m in pat.finditer(src):
            by_name[m.group(1)].append((os.path.relpath(path, root), m.group(2).replace("_", "")))
    for name, occurrences in sorted(by_name.items()):
        values = {v for _, v in occurrences}
        if len(values) <= 1:
            continue
        modules = {p.split(os.sep)[0] for p, _ in occurrences}
        if len(modules) <= 1:
            continue
        findings.append({
            "rule": "R1_duplicate_constant",
            "severity": "P1",
            "symbol": name,
            "values": sorted(values),
            "modules": sorted(modules),
            "locations": ["{}={}".format(p, v) for p, v in occurrences],
            "note": "常量 {} 跨模块重名且取值不同，可能语义漂移；建议收敛到单一真相源".format(name),
        })
    return findings


def rule_stateflow_distinct(root, files):
    """R2 对 StateFlow 调 distinctUntilChanged（Kotlin 标为 ERROR 级 deprecation → 编译失败）。

    精度：combine/map/flow 返回的 Flow 上调用是合法的，仅当上游确为本文件声明的
    StateFlow（属性声明 / MutableStateFlow / asStateFlow）时才判 P0。
    """
    findings = []
    for path in files:
        src = strip_comments(read(path))
        lines = src.split("\n")
        stateflow_names = set()
        for m in re.finditer(
            r"(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*StateFlow<[^>]*>)?\s*=\s*"
            r"(?:[A-Za-z0-9_.]*\.)?(?:MutableStateFlow\(|asStateFlow\()",
            src,
        ):
            stateflow_names.add(m.group(1))
        for m in re.finditer(r"(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*StateFlow<", src):
            stateflow_names.add(m.group(1))
        for i, line in enumerate(lines):
            if ".distinctUntilChanged()" not in line:
                continue
            head = line.replace(".distinctUntilChanged()", "")
            tail = " ".join(lines[max(0, i - 4):i + 1]).replace(".distinctUntilChanged()", "")
            legal = re.search(
                r"(combine\s*\(|\.map\s*\{|\.mapLatest\s*\{|flow\s*\{|flowOf\s*\(|"
                r"\.flatMapLatest\s*\{|\.transform\s*\{|\.filter\s*\{)",
                tail,
            )
            ident = re.search(r"([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*$", head.strip())
            name = ident.group(1) if ident else None
            if name and name in stateflow_names:
                findings.append({
                    "rule": "R2_stateflow_distinct",
                    "severity": "P0",
                    "file": os.path.relpath(path, root),
                    "line": i + 1,
                    "symbol": name,
                    "code": line.strip()[:160],
                    "note": "对 StateFlow 变量 {} 调用 distinctUntilChanged：StateFlow 已按值去重，该调用无效果，"
                             "且 Kotlin 将其标注为 ERROR 级 deprecation（非普通 warning，无法通过 allWarningsAsErrors 开关规避），"
                             "会导致编译失败，必须删除。".format(name),
                })
            elif not legal and not name:
                findings.append({
                    "rule": "R2_stateflow_distinct",
                    "severity": "P2",
                    "file": os.path.relpath(path, root),
                    "line": i + 1,
                    "symbol": "(需人工确认上游类型)",
                    "code": line.strip()[:160],
                    "note": "存在 distinctUntilChanged，但无法自动判定上游类型，请人工确认",
                })
    return findings


def rule_combine_swallow_updates(root, files):
    """R3 combine 输出恒定 + 后跟 distinctUntilChanged → 其余上游变化被吞。"""
    findings = []
    for path in files:
        src = strip_comments(read(path))
        for m in re.finditer(r"combine\s*\(", src):
            start = m.end()
            depth, idx = 1, start
            while idx < len(src) and depth > 0:
                if src[idx] == "(":
                    depth += 1
                elif src[idx] == ")":
                    depth -= 1
                idx += 1
            args_region = src[start:idx - 1]
            depth2, commas = 0, 0
            for ch in args_region:
                if ch in "([{":
                    depth2 += 1
                elif ch in ")]}":
                    depth2 -= 1
                elif ch == "," and depth2 == 0:
                    commas += 1
            stream_count = commas + 1
            if stream_count < 2:
                continue
            rest = src[idx:idx + 260]
            lam = re.match(
                r"\s*\)?\s*\{\s*([A-Za-z_][A-Za-z0-9_]*\s*(?:,\s*[A-Za-z_][A-Za-z0-9_]*\s*)*)->",
                rest,
            )
            if not lam:
                continue
            params = [p.strip() for p in lam.group(1).split(",")]
            kept = [p for p in params if p != "_"]
            dropped = [p for p in params if p == "_"]
            if not dropped or len(kept) != 1:
                continue
            body = rest[lam.end():lam.end() + 80]
            if not re.match(r"\s*" + re.escape(kept[0]) + r"\s*[!?]?\s*\}", body):
                continue
            tail_region = src[idx + lam.end(): idx + lam.end() + 400].replace(" ", "").replace("\n", "")
            if ".distinctUntilChanged()" not in tail_region:
                continue
            findings.append({
                "rule": "R3_combine_swallow_updates",
                "severity": "P0",
                "file": os.path.relpath(path, root),
                "line": src[:m.start()].count("\n") + 1,
                "streams": stream_count,
                "dropped_streams": len(dropped),
                "kept": kept[0],
                "note": ("combine 合并 {} 个流，但输出被压缩为恒定的 {} 值，再经 distinctUntilChanged 去重："
                         "另外 {} 个流的任何变化都会被吞掉，相关 UI 只在 {} 变化时才刷新").format(
                    stream_count, kept[0], len(dropped), kept[0]),
            })
    return findings


def rule_default_value_divergence(root, files):
    """R4 同一偏好键默认值多处不一致。"""
    findings = []
    pat = re.compile(r"\?\:\s*([0-9][0-9_]*|true|false)\b")
    by_key = defaultdict(list)
    for path in files:
        src = strip_comments(read(path))
        for i, line in enumerate(src.split("\n")):
            if "Preferences.data" not in line and "preferencesKey" in line:
                continue
            if "Preferences.data" not in line:
                continue
            for m in pat.finditer(line):
                key = re.search(r"\[([A-Za-z_][A-Za-z0-9_]*)\]", line)
                if key:
                    by_key[key.group(1)].append((os.path.relpath(path, root), i + 1, m.group(1)))
    for key, occ in sorted(by_key.items()):
        values = {v for _, _, v in occ}
        if len(values) > 1:
            findings.append({
                "rule": "R4_default_value_divergence",
                "severity": "P2",
                "key": key,
                "values": sorted(values),
                "locations": ["{}:{}={}".format(f, ln, v) for f, ln, v in occ],
                "note": "偏好键 {} 的默认值在多处不一致".format(key),
            })
    return findings


def rule_orphan_preference_keys(root, files):
    """R5 偏好键只写不读（僵尸设置）。"""
    findings = []
    store_file, keys = None, {}
    for path in files:
        if not path.endswith("SettingsDataStore.kt"):
            continue
        store_file = path
        src = strip_comments(read(path))
        for m in re.finditer(r"private val (\w+Key)\s*=\s*[\w.]*preferencesKey\(\"([^\"]+)\"\)", src):
            keys[m.group(1)] = m.group(2)
    if not keys or store_file is None:
        return findings
    all_src = "\n".join(strip_comments(read(p)) for p in files if p != store_file)
    for var, key in sorted(keys.items()):
        stem = var[:-3] if var.endswith("Key") else var
        if stem not in all_src:
            findings.append({
                "rule": "R5_orphan_preference_key",
                "severity": "P1",
                "key": key,
                "var": var,
                "note": '偏好键 "{}"（{}）在 SettingsDataStore 之外无引用，疑似僵尸设置'.format(key, var),
            })
    return findings


# RULES 表在文件末尾定义（所有规则函数之后），
# 因为 Python 按顺序执行，此处引用尚未定义的函数会抛 NameError。


# ---------------------------------------------------------------- R6
# 只匹配「会真正执行 IO 的挂起调用」；observeXxx 返回 Flow，本身异步，不算主线程阻塞。
# 命名约定：DAO 的订阅式查询统一以 observe 开头（见项目内 observeAll/observeLanes/
# observeActivePlan/observeScratchpads 等），故排除 observe 前缀即可去掉绝大部分误报。
SUSPEND_IN_FLOW = re.compile(
    r"(mapLatest|flatMapLatest|transformLatest|map|filter|flatMapConcat|flatMapMerge)\s*(\([^)]*\))?\s*\{",
)
BLOCKING_CALL = re.compile(
    r"\b\w*(repo|Repo|repository|Repository|dao|Dao|store|Store|manager|Manager)"
    r"\.(?!observe)(\w+)\s*\(",
)


def rule_suspend_call_on_main(root, files):
    """R6 Flow 链内调用挂起 IO 方法，却无 flowOn → 重活跑在 Main。

    原理：`stateIn(viewModelScope, ...)` 的共享协程运行在 viewModelScope 的调度器
    （Dispatchers.Main.immediate）。若上游 lambda 里调用了会真正执行 IO 的挂起方法
    （如 dao.insertAll / dao.findById / repo.upsert），而整条链没有 flowOn，
    这些 IO 就在主线程执行——典型症状是「刚进入页面卡一下」（首订阅即触发）。

    精度：排除 `observe*`（返回 Flow、本身异步，不阻塞主线程）。
    本规则为启发式提示（P1），需人工确认被调用方法是否真为挂起 IO。
    """
    findings = []
    for path in files:
        src = strip_comments(read(path))
        lines = src.split("\n")
        for i, line in enumerate(lines):
            m = SUSPEND_IN_FLOW.search(line)
            if not m:
                continue
            window = "\n".join(lines[i:i + 18])
            call = BLOCKING_CALL.search(window)
            if not call:
                continue
            scope = "\n".join(lines[max(0, i - 25):i + 40])
            if "flowOn(" in scope:
                continue
            findings.append({
                "rule": "R6_suspend_call_on_main",
                # 降级为 P2（提示）：静态分析无法判断该调用是否已被外层 withContext(IO)
                # 包裹，也无法确认方法是否真为挂起 IO（命名约定只是启发式）。
                # 保留为提示，供人工快速定位候选点，不作为门禁阻断。
                "severity": "P2",
                "file": os.path.relpath(path, root),
                "line": i + 1,
                "suspect_call": call.group(0).strip()[:80],
                "note": ("Flow 的 lambda（{}）内出现疑似挂起 IO 调用（{}），该链 40 行内未见 flowOn。"
                         "请人工确认：① 该方法是否真为挂起 IO（observe* 已排除）；"
                         "② 是否已被外层 withContext(Dispatchers.IO) 包裹；"
                         "③ 若是 UI 首订阅路径且未切线程，建议补 .flowOn(Dispatchers.Default/IO)。").format(
                    m.group(1), call.group(0).strip()[:60]),
            })
    return findings


# ---------------------------------------------------------------- R7
HEAVY_COMPOSITION = re.compile(
    r"(filterIsInstance|indexOfFirst|indexOfLast|associateBy|groupBy|sortedBy|\.sumOf|\.sum\(\)|flatMap|distinct)",
)


def rule_unremembered_heavy_composition(root, files):
    """R7 组合函数内未包 remember 的重活，且依赖随流式高频变化的数据。

    判定：文件含 @Composable 时，查找 `val x = <表达式含重算子>` 且：
      - 不以 `remember` 开头（bare val 赋值）
      - 表达式里引用了形如 messages/documents/items/entries 这类「列表型高频变量」
    报 P2（提示），因为 Compose 侧无法完全静态判断是否每帧执行。
    """
    findings = []
    listish = re.compile(r"\b(messages|docs|documents|entries|items|nodes|logs|events)\b")
    for path in files:
        src = strip_comments(read(path))
        if "@Composable" not in src:
            continue
        for i, line in enumerate(src.split("\n")):
            stripped = line.strip()
            if not stripped.startswith("val "):
                continue
            if "remember" in stripped or "derivedStateOf" in stripped:
                continue
            if "=" not in stripped:
                continue
            rhs = stripped.split("=", 1)[1]
            if not HEAVY_COMPOSITION.search(rhs):
                continue
            if not listish.search(rhs):
                continue
            findings.append({
                "rule": "R7_unremembered_heavy_composition",
                "severity": "P2",
                "file": os.path.relpath(path, root),
                "line": i + 1,
                "code": stripped[:150],
                "note": "Composable 内对列表型数据做重算子但未包 remember/derivedStateOf，"
                        "流式或频繁重组时可能每帧重算；建议包 remember(稳定键) 缩小依赖。",
            })
    return findings


# ---------------------------------------------------------------- R8
def rule_flatmap_once_snapshot(root, files):
    """R8 flow{} 内一次性 emit 快照 + 上游去重 → 数据变化不刷新。

    这是 D4（草稿便签）与 activeCompaction 的病灶形态：
      combine(...) { ... }.distinctUntilChanged().flatMapLatest { flow { emit(dao.read(id)) } }
    上游去重后值不变、且下游只读一次，DB 变化自然不传导。
    """
    findings = []
    for path in files:
        src = strip_comments(read(path))
        lines = src.split("\n")
        for i, line in enumerate(lines):
            if "flatMapLatest" not in line:
                continue
            window = "\n".join(lines[i:i + 10])
            if not re.search(r"flow\s*\{[^}]*emit\s*\(", window, re.S):
                continue
            back = "\n".join(lines[max(0, i - 12):i])
            if "distinctUntilChanged" not in back:
                continue
            findings.append({
                "rule": "R8_flatmap_once_snapshot",
                "severity": "P1",
                "file": os.path.relpath(path, root),
                "line": i + 1,
                "code": line.strip()[:150],
                "note": ("上游含 distinctUntilChanged，下游 flatMapLatest 内用 flow{emit(一次性读取)}："
                         "上游值不变时不会重读，数据源后续变化无法传导（UI 表现为「改了没反应」）。"
                         "建议改为订阅数据源的 Flow（如 DAO 的 observeXxx）。"),
            })
    return findings


# ---------------------------------------------------------------- R9
ASSERT_WITH_ARGS = re.compile(r"\bassert(True|False)\s*\((.+)\)\s*$")


def rule_assert_argument_order(root, files):
    """R9 JUnit4 断言参数顺序写反：assertTrue(条件, "消息") → 编译期报 Int/Long 类型不匹配。

    本项目单测用 JUnit 4（org.junit.Assert），消息版签名为 assertTrue(String, boolean)，
    消息在前。若写成 (condition, "msg")，编译器找不到匹配重载，会报出难以理解的
    「Argument type mismatch: actual type is 'Int', but 'Long' was expected」之类错误，
    而报错位置指向断言行却不点明参数顺序——实测排查成本高。

    本规则只在「第一个实参不是字符串、第二个实参是字符串」时报，零误报。
    """
    findings = []
    for path in files:
        src = strip_comments(read(path))
        for i, line in enumerate(src.split("\n"), 1):
            m = ASSERT_WITH_ARGS.search(line)
            if not m:
                continue
            args = m.group(2)
            depth, parts, cur = 0, [], ""
            for ch in args:
                if ch in "([{":
                    depth += 1
                elif ch in ")]}":
                    depth -= 1
                if ch == "," and depth == 0:
                    parts.append(cur)
                    cur = ""
                else:
                    cur += ch
            parts.append(cur)
            if len(parts) != 2:
                continue
            first, second = parts[0].strip(), parts[1].strip()
            first_is_msg = first.startswith('"')
            second_is_msg = second.startswith('"')
            if not first_is_msg and second_is_msg:
                findings.append({
                    "rule": "R9_assert_argument_order",
                    "severity": "P1",
                    "file": os.path.relpath(path, root),
                    "line": i,
                    "code": line.strip()[:150],
                    "note": ("JUnit4 的 assertTrue/assertFalse 消息版签名为 (message, condition)，"
                             "此处写成 (condition, message) 会编译失败并报出难以定位的类型不匹配；"
                             "请把消息字符串移到第一个参数。"),
                })
    return findings


# ---------------------------------------------------------------- R10
UNUSED_IMPORT = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", re.M)
# `by` 委托所需的运算符 import：名字不出现在代码里但必须有 import。
DELEGATE_WHITELIST = {"getValue", "setValue", "provideDelegate", "getValue0"}


def rule_unused_import(root, files):
    """R10 未使用的 import（卫生问题，非编译失败）。

    更正前提：本项目**并未**开启 allWarningsAsErrors（已 grep 确认无该配置），
    因此未使用的 import 只是 warning，不会导致编译失败——此前把它写成 P1「会编译失败」
    是错误判断。现降级为 P2 提示，仅用于清理冗余 import。

    已知白名单：Compose 属性委托所需的 getValue/setValue（`by` 语法必需，名字不出现）。
    """
    findings = []
    for path in files:
        raw = read(path)
        body = strip_comments(raw)
        for m in UNUSED_IMPORT.finditer(body):
            full, alias = m.group(1), m.group(2)
            name = alias or full.split(".")[-1]
            if name in DELEGATE_WHITELIST:
                continue
            # 去掉该 import 行之后，检查名字是否在文件其余位置出现
            rest = body[:m.start()] + body[m.end():]
            rest = re.sub(r"^import\s+.*$", "", rest, flags=re.M)
            if re.search(r"\b" + re.escape(name) + r"\b", rest):
                continue
            line_no = body[:m.start()].count("\n") + 1
            findings.append({
                "rule": "R10_unused_import",
                "severity": "P2",
                "file": os.path.relpath(path, root),
                "line": line_no,
                "symbol": full,
                "note": "import {} 在文件中未被使用，可删除（仅卫生问题，不影响编译）。".format(full),
            })
    return findings


# ---------------------------------------------------------------- R11
DAO_FUN = re.compile(r"^\s*(?:suspend\s+)?fun\s+(\w+)\s*\(", re.M)


def rule_repository_interface_drift(root, files):
    """【已停用·保留备查】DAO 方法未出现在 Repository 层。

    停用原因（实测评估后否决）：
      规则假设「DAO 的每个方法都应在 Repository 接口/实现/Fake 中出现」，但这是错的——
      DAO 内部互相调用的私有辅助方法（如 insertEntry 仅供 insertEntryOrThrow 使用）
      本就不该暴露到 Repository 层。在真实代码库上实测产生 16 条报告，人工抽查全部为
      正常设计而非缺陷，误报率高、价值低，故不注册进 RULES。

    保留此函数是为了留下评估记录；若将来需要「接口与实现确实漂移」的检查，
    应以「接口方法是否都有对应实现」为判据，而非「DAO 方法是否都被提到」。

    原规则说明：R11 DAO 新增方法后，Repository 接口与其 Fake 实现未同步 → 编译失败。

    原理：项目里 Repository 是接口 + RoomXxxRepository 实现 + 测试 FakeXxx 实现。
    给 DAO 加方法后若忘记同步接口/Fake，测试模块会报「未实现抽象成员」。
    实测此坑出现过（observeActivePlan / observeScratchpads 两次）。

    判定：对每个 XxxDao.kt 里的 public DAO 函数名集合，检查同名前缀的
    interface 定义文件与测试目录下的 Fake 实现是否都出现了该方法名。
    仅在「DAO 有、接口没有」或「接口有、Fake 没有」时报。

    为控制误报，只在能明确找到对应接口文件时判定。
    """
    findings = []
    # 收集 DAO 方法
    dao_funcs = {}
    for path in files:
        base = os.path.basename(path)
        if not base.endswith("Dao.kt"):
            continue
        src = strip_comments(read(path))
        # 只取 interface 块内的 fun（DAO 文件通常是 interface）
        if "interface " not in src:
            continue
        names = set()
        for m in DAO_FUN.finditer(src):
            n = m.group(1)
            if n.startswith("_"):
                continue
            names.add(n)
        if names:
            dao_funcs[base[:-3]] = (path, names)

    if not dao_funcs:
        return findings

    for dao_name, (dao_path, names) in sorted(dao_funcs.items()):
        # 扫描**所有**文件（不按文件名过滤——实现类可能叫 PersistenceRepositories.kt 等，
        # 早期版本按 'Repository' 子串过滤文件名，漏掉了复数命名的实现文件，造成大量误报）。
        # 同时排除 DAO 自身文件：否则方法名总能在自己的定义处匹配到，规则永远不触发。
        dao_abs = os.path.abspath(dao_path)
        all_bodies = [
            strip_comments(read(p))
            for p in files
            if os.path.abspath(p) != dao_abs
        ]
        for name in sorted(names):
            mentioned = any(
                re.search(r"\b" + re.escape(name) + r"\b", body)
                for body in all_bodies
                if body
            )
            if not mentioned:
                findings.append({
                    "rule": "R11_repository_interface_drift",
                    "severity": "P2",
                    "file": os.path.relpath(dao_path, root),
                    "symbol": name,
                    "note": ("DAO 方法 {} 未在任何 Repository/实现文件中出现，"
                             "新增 DAO 方法后需同步 Repository 接口、Room 实现与测试 Fake，"
                             "否则测试模块编译失败（本坑已出现两次）。").format(name),
                })
    return findings


# ---------------------------------------------------------------- R12
OFFSCREEN = re.compile(r"CompositingStrategy\.Offscreen")
SCROLL_CONTAINER = re.compile(r"\b(LazyColumn|LazyRow|LazyVerticalGrid|LazyHorizontalGrid|"
                              r"Modifier\.verticalScroll|Modifier\.horizontalScroll|"
                              r"rememberLazyListState|rememberScrollState)\b")
# 允许的白名单场景：一次性全屏遮罩/引导层（不在滚动容器内），保留 Offscreen 是合理的。
OFFSCREEN_ALLOW_HINT = re.compile(r"(Spotlight|Guide|Tour|Overlay|Mask|Scrim)", re.I)


def rule_offscreen_compositing_in_scroll(root, files):
    """R12 滚动容器上使用 CompositingStrategy.Offscreen → 滚动每帧离屏合成（滑动卡顿头号成因）。

    原理：`BlendMode.DstIn` 之类的遮罩必须先把内容渲染到独立离屏缓冲才能生效，
    因此 `CompositingStrategy.Offscreen` 会让**被挂载的那个节点每帧全量离屏合成**。
    若挂在 LazyColumn / 滚动容器（或它们的父节点）上，代价与「列表面积 × 帧率」成正比，
    表现为**静止不卡、一滑就卡**——2026-09-14 太墟聊天列表滑动卡顿的真实根因即此。

    判定：同一文件内出现 Offscreen，且该修饰符所挂的节点上下文里有滚动容器特征
    （LazyColumn / rememberScrollState / verticalScroll 等），文件类型名不在引导层白名单。
    报 P1：需人工确认该节点是否处于滚动路径上。
    """
    findings = []
    for path in files:
        src = strip_comments(read(path))
        lines = src.split("\n")
        base = os.path.basename(path)
        if OFFSCREEN_ALLOW_HINT.search(base):
            continue
        for i, line in enumerate(lines):
            if not OFFSCREEN.search(line):
                continue
            # 前后 30 行窗口内是否出现滚动容器
            window = "\n".join(lines[max(0, i - 30):i + 30])
            if not SCROLL_CONTAINER.search(window):
                continue
            findings.append({
                "rule": "R12_offscreen_compositing_in_scroll",
                "severity": "P1",
                "file": os.path.relpath(path, root),
                "line": i + 1,
                "code": line.strip()[:150],
                "note": ("滚动容器附近使用 CompositingStrategy.Offscreen：该节点每帧都要离屏合成，"
                         "滚动期间代价与列表面积×帧率成正比（典型症状：静止不卡、一滑就卡）。"
                         "建议改用「在滚动内容之上叠加渐变/遮罩层」的等价实现，避免离屏合成。"),
            })
    return findings


# 规则注册表：必须位于所有规则函数定义之后（Python 顺序执行，前置引用会 NameError）。
RULES = [
    ("R1_duplicate_constant", rule_duplicate_constants),
    ("R2_stateflow_distinct", rule_stateflow_distinct),
    ("R3_combine_swallow_updates", rule_combine_swallow_updates),
    ("R4_default_value_divergence", rule_default_value_divergence),
    ("R5_orphan_preference_key", rule_orphan_preference_keys),
    ("R6_suspend_call_on_main", rule_suspend_call_on_main),
    ("R7_unremembered_heavy_composition", rule_unremembered_heavy_composition),
    ("R8_flatmap_once_snapshot", rule_flatmap_once_snapshot),
    ("R9_assert_argument_order", rule_assert_argument_order),
    ("R10_unused_import", rule_unused_import),
    ("R12_offscreen_compositing_in_scroll", rule_offscreen_compositing_in_scroll),
]


def run_audit(root, include_tests=False, rule_filter=None):
    files = list(iter_sources(root, include_tests=include_tests))
    findings = []
    for name, fn in RULES:
        if rule_filter and name not in rule_filter:
            continue
        try:
            findings.extend(fn(root, files))
        except Exception as exc:  # 单条规则出错不影响整体
            findings.append({
                "rule": name, "severity": "P2", "error": str(exc),
                "note": "规则执行异常",
            })
    order = {"P0": 0, "P1": 1, "P2": 2}
    findings.sort(key=lambda f: (order.get(f.get("severity"), 9), f.get("rule", "")))
    return files, findings


def render_report(files, findings, root):
    lines = []
    lines.append("扫描 {} 个 Kotlin 文件（根目录 {}）".format(len(files), root))
    lines.append("=" * 78)
    lines.append("因果链闭环审计结果")
    lines.append("=" * 78)
    if not findings:
        lines.append("\n未发现结构性缺陷。")
    for f in findings:
        lines.append("\n[{}] {}".format(f.get("severity"), f.get("rule")))
        for k, v in f.items():
            if k in ("rule", "severity", "note"):
                continue
            if isinstance(v, list):
                for item in v:
                    lines.append("    {}: {}".format(k, item))
            else:
                lines.append("    {}: {}".format(k, v))
        lines.append("    说明: {}".format(f.get("note")))
    counts = defaultdict(int)
    for f in findings:
        counts[f.get("rule")] += 1
    lines.append("\n" + "=" * 78)
    lines.append("汇总")
    lines.append("=" * 78)
    for rule, n in sorted(counts.items()):
        lines.append("  {}: {}".format(rule, n))
    lines.append("  合计: {}".format(len(findings)))
    p0 = [f for f in findings if f.get("severity") == "P0"]
    lines.append("  P0（必须修复）: {}".format(len(p0)))
    return "\n".join(lines), len(p0)


# ============================== MCP 工具 ==============================

def tool_audit(args):
    root = ensure_workspace(args.get("project_path") or args.get("workspace") or "/workspace")
    include_tests = bool(args.get("include_tests", False))
    rule_filter = args.get("rules")
    files, findings = run_audit(root, include_tests=include_tests, rule_filter=rule_filter)
    report, p0 = render_report(files, findings, root)
    if p0:
        report += "\n\n结论：存在 {} 个 P0 级缺陷（StateFlow 误用 / combine 吞更新），必须修复。".format(p0)
    else:
        report += "\n\n结论：P0 = 0。P1/P2 需人工判断是否属真缺陷（如各通知 ID 不同属正常）。"
    json_path = args.get("json_output")
    if json_path:
        try:
            with open(json_path, "w", encoding="utf-8") as fh:
                json.dump(findings, fh, ensure_ascii=False, indent=2)
            report += "\n\n已写出 JSON 报告: {}".format(json_path)
        except Exception as exc:
            report += "\n\n写出 JSON 失败: {}".format(exc)
    return report


def tool_list_rules(_args):
    lines = ["因果链闭环审计规则："]
    desc = {
        "R1_duplicate_constant": "同名常量跨模块重名且取值不同（单一真相源缺失）",
        "R2_stateflow_distinct": "对 StateFlow 调 distinctUntilChanged（ERROR 级 deprecation，编译失败）",
        "R3_combine_swallow_updates": "combine 输出恒定 + 下游去重 → 其余上游变化被吞（UI 不刷新）",
        "R4_default_value_divergence": "同一偏好键默认值多处不一致",
        "R5_orphan_preference_key": "偏好键只写不读（僵尸设置）",
        "R6_suspend_call_on_main": "Flow 链内调用 IO 方法却无 flowOn → 重活跑在 Main（首屏卡顿常见成因）",
        "R7_unremembered_heavy_composition": "Composable 内对列表做重算子但未 remember（重组时可能每帧重算）",
        "R8_flatmap_once_snapshot": "上游去重 + flatMapLatest 内一次性读取 → 数据源变化不传导（改了没反应）",
        "R9_assert_argument_order": "JUnit4 断言参数顺序写反 assertTrue(条件, 消息) → 编译报类型不匹配",
        "R10_unused_import": "未使用的 import（仅卫生问题，不影响编译）",
        "R12_offscreen_compositing_in_scroll": "滚动容器上使用 CompositingStrategy.Offscreen → 滚动每帧离屏合成（滑动卡顿头号成因）",
    }
    for name, _fn in RULES:
        lines.append("  - {}: {}".format(name, desc.get(name, "")))
    lines.append("")
    lines.append("严重度：P0 = 必然导致行为缺陷，必须修；P1/P2 = 需人工判断。")
    return "\n".join(lines)


def tool_explain(args):
    topic = (args.get("topic") or "").strip()
    base = (
        "因果链缺陷的判定原理：\n\n"
        "1) combine 丢参数 ≠ 不触发\n"
        "   combine(a, b, c) { x, _, _ -> x } 里的 `_` 只是不使用该参数；\n"
        "   combine 任一上游发射都会重跑 lambda —— 丢参数不会阻止触发。\n\n"
        "2) 真正的缺陷是「输出恒定 + 下游去重」\n"
        "   combine(...) { x, _, _ -> x } 输出恒等于 x，\n"
        "   紧跟 .distinctUntilChanged() 后，只要 x 未变，其余上游的变化全被吞掉。\n"
        "   典型症状：DB 写入了、state 变了，但界面不刷新，要切换会话/重进页面才更新。\n\n"
        "3) StateFlow 上禁止 distinctUntilChanged\n"
        "   StateFlow 本身已按值去重，再调是 Kotlin 标记的 deprecated（无效果），\n"
        "   且 Kotlin 将其标注为 ERROR 级 deprecation → 直接编译失败（与 allWarningsAsErrors 开关无关）。\n\n"
        "4) 修复范式（同一病根，四处已修）\n"
        "   - 数据表驱动 UI：改为订阅 Room 的 Flow 查询（observeXxx），而非一次性快照；\n"
        "   - 多上游共同决定：把各上游压成可比较信号（如 data class ProjectionKey）再做去重；\n"
        "   - 会话切换清理：用 currentSessionId.drop(1).collect { ... } 统一重置会话级 UI 状态。"
    )
    if topic:
        base += "\n\n（查询主题：{}）".format(topic)
    return base


TOOLS = [
    {
        "name": "causal_audit_scan",
        "description": "对工作区做因果链闭环静态审计：检出单一真相源缺失、StateFlow 误用、"
                       "combine 吞更新、默认值分歧、僵尸设置等结构性缺陷。返回按严重度排序的缺陷清单。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "project_path": {"type": "string", "description": "待审计的项目根目录，默认 /workspace"},
                "include_tests": {"type": "boolean", "description": "是否包含测试源码，默认 false"},
                "rules": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "仅运行指定规则（如 [\"R3_combine_swallow_updates\"]），省略则全部运行",
                },
                "json_output": {"type": "string", "description": "可选：把 JSON 结果写入该路径"},
            },
        },
    },
    {
        "name": "causal_audit_rules",
        "description": "列出因果链审计的全部检测规则及其含义与严重度分级。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "causal_audit_explain",
        "description": "解释因果链缺陷的判定原理与修复范式（combine 丢参数 vs 输出恒定+去重的区别、"
                       "StateFlow 误用、四类已修的同源缺陷修法）。",
        "inputSchema": {
            "type": "object",
            "properties": {"topic": {"type": "string", "description": "可选：想深入了解的主题关键词"}},
        },
    },
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default="/workspace")
    args = parser.parse_args()
    default_root = args.repository

    handlers = {
        "causal_audit_scan": lambda a: tool_audit({**a, "project_path": a.get("project_path") or default_root}),
        "causal_audit_rules": tool_list_rules,
        "causal_audit_explain": tool_explain,
    }

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            method, req_id = req.get("method"), req.get("id")
            if method == "initialize":
                out = response(req_id, {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": "taixu-causal-audit", "version": "1.0.0"},
                })
            elif method == "notifications/initialized":
                continue
            elif method == "tools/list":
                out = response(req_id, {"tools": TOOLS})
            elif method == "tools/call":
                params = req.get("params") or {}
                try:
                    name = params.get("name")
                    tool_args = params.get("arguments") or {}
                    handler = handlers.get(name)
                    if handler is None:
                        raise ValueError("未知工具: " + str(name))
                    text = handler(tool_args)
                    result = {"content": [{"type": "text", "text": truncate(text)}], "isError": False}
                except Exception as exc:
                    result = {"content": [{"type": "text", "text": str(exc)}], "isError": True}
                out = response(req_id, result)
            else:
                continue
            sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
            sys.stdout.flush()
        except Exception as exc:
            sys.stdout.write(json.dumps(response(None, error={"code": -32603, "message": str(exc)}), ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
