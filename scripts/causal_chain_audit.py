#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
太墟因果链闭环审计器（causal-chain-audit）

目的：用机械可验证的静态分析，系统性找出「同一语义在多处实现/读取/显示不一致」
的结构性缺陷，替代靠人肉模式搜索的打地鼠做法。

五类检测规则：
  R1  单一真相源缺失：同名字面量/常量在多个文件重复定义且值不同
  R2  StateFlow 误用：对 StateFlow 调 distinctUntilChanged（本项目按 error）
  R3  combine 丢参数：combine(a,b,c){ x,_,_ -> x } 形式，后两个流的变化被吞
  R4  默认值双源：同一语义的默认值在多处硬编码不一致
  R5  偏好键孤岛：DataStore 偏好键被写入但无任何读取方（僵尸设置）

用法：
  python3 scripts/causal_chain_audit.py [--root .] [--json out.json]
"""
import argparse
import json
import os
import re
import sys
from collections import defaultdict

SKIP_DIRS = {'build', '.git', '.gradle', '.idea', 'node_modules', '.codegraph'}
KT_SUFFIXES = ('.kt', '.kts')


def iter_sources(root, include_tests=True):
    """遍历 Kotlin 源文件。"""
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        if not include_tests and f'{os.sep}test{os.sep}' in dirpath + os.sep:
            continue
        for fn in filenames:
            if fn.endswith(KT_SUFFIXES):
                yield os.path.join(dirpath, fn)


def read(path):
    try:
        with open(path, 'r', encoding='utf-8') as f:
            return f.read()
    except Exception:
        return ''


def strip_comments(src):
    """去掉行注释与块注释，避免注释里的示例代码造成误报。"""
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


def rel(root, path):
    return os.path.relpath(path, root)


# ---------------------------------------------------------------- R1
def rule_duplicate_constants(root, files):
    """R1 同名单字面量在多个文件定义且值不同（单一真相源缺失）。

    精度说明：不同类各自持有的 private const（如各通知用不同 NOTIFICATION_ID、
    各服务各自的 READ_TIMEOUT_MS）是常见且合理的，不算缺陷。
    因此仅当这些同名常量分布在 **不同 Gradle 模块**（路径首段不同）且取值不同时上报，
    提示可能存在跨模块语义漂移；同模块内一律不报。
    """
    findings = []
    pat = re.compile(
        r'^\s*(?:private\s+|internal\s+|public\s+)*const\s+val\s+([A-Z][A-Z0-9_]*)\s*[:=]\s*'
        r'(?:Int|Long|Float|Double)?\s*[=]?\s*([0-9][0-9_]*|[0-9_]*[0-9])\b',
        re.M)
    by_name = defaultdict(list)
    for path in files:
        src = strip_comments(read(path))
        for m in pat.finditer(src):
            name, value = m.group(1), m.group(2).replace('_', '')
            r = rel(root, path)
            by_name[name].append((r, value))
    for name, occurrences in sorted(by_name.items()):
        values = {v for _, v in occurrences}
        if len(values) <= 1:
            continue
        # 模块即路径首段（如 harness/、runtime/、feature/）
        modules = {r.split(os.sep)[0] for r, _ in occurrences}
        if len(modules) <= 1:
            continue  # 同模块内的重名常量不视为缺陷
        findings.append({
            'rule': 'R1_duplicate_constant',
            'symbol': name,
            'values': sorted(values),
            'modules': sorted(modules),
            'locations': [f'{f}={v}' for f, v in occurrences],
            'severity': 'P1',
            'note': f'常量 {name} 跨模块重名且取值不同（{" vs ".join(sorted(values))}），'
                    f'可能存在语义漂移；建议收敛到单一真相源',
        })
    return findings


# ---------------------------------------------------------------- R2
def rule_stateflow_distinct(root, files):
    """R2 对 StateFlow 调 distinctUntilChanged（本项目 allWarningsAsErrors → 编译失败）。

    精度说明：`combine(...)` / `map {}` / `flow {}` / DAO 返回的 Flow 上调用
    distinctUntilChanged 是**合法且常见**的；只有上游确实是 StateFlow（属性声明的
    `: StateFlow<...>`、`MutableStateFlow(...)`、`.asStateFlow()`）时才构成缺陷。
    因此本规则只在能确证上游为 StateFlow 时判 P0，其余仅作为待人工确认的提示（P2）。
    """
    findings = []
    for path in files:
        src = strip_comments(read(path))
        lines = src.split('\n')

        # 先收集本文件中所有 StateFlow 类型的属性/变量名
        stateflow_names = set()
        for m in re.finditer(
            r'(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*StateFlow<[^>]*>)?\s*=\s*'
            r'(?:[A-Za-z0-9_.]*\.)?(?:MutableStateFlow\(|asStateFlow\(\))', src):
            stateflow_names.add(m.group(1))
        for m in re.finditer(r'(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*StateFlow<', src):
            stateflow_names.add(m.group(1))

        for i, line in enumerate(lines):
            if '.distinctUntilChanged()' not in line:
                continue
            # 取本行去掉该调用后的尾部，以及往上 4 行的表达式尾部
            head = line.replace('.distinctUntilChanged()', '')
            tail = ' '.join(lines[max(0, i - 4):i + 1]).replace('.distinctUntilChanged()', '')
            # 上游是否 combine/map/flow 等 Flow 构造（那样就是合法的）
            legal_source = re.search(
                r'(combine\s*\(|\.map\s*\{|\.mapLatest\s*\{|flow\s*\{|flowOf\s*\(|'
                r'\.flatMapLatest\s*\{|\.transform\s*\{|\.filter\s*\{)', tail)
            # 直接调用对象名
            ident = re.search(r'([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*$', head.strip())
            name = ident.group(1) if ident else None
            if name and name in stateflow_names:
                findings.append({
                    'rule': 'R2_stateflow_distinct',
                    'file': rel(root, path),
                    'line': i + 1,
                    'symbol': name,
                    'severity': 'P0',
                    'note': f'对 StateFlow 变量 {name} 调用 distinctUntilChanged：无效果且本项目按 error，必须删除',
                    'code': line.strip()[:160],
                })
            elif not legal_source and not name:
                findings.append({
                    'rule': 'R2_stateflow_distinct',
                    'file': rel(root, path),
                    'line': i + 1,
                    'symbol': '(需人工确认上游类型)',
                    'severity': 'P2',
                    'note': '存在 distinctUntilChanged，但无法自动判定上游类型（非本文件声明的 StateFlow），请人工确认',
                    'code': line.strip()[:160],
                })
    return findings


# ---------------------------------------------------------------- R3
def rule_combine_swallow_updates(root, files):
    """R3 combine 输出恒定 + 后跟 distinctUntilChanged/flatMapLatest，导致上游变化被吞。

    重要原理澄清（避免此前的误判）：
      `combine(a, b, c) { x, _, _ -> x }` 里的 `_` 只是「不使用该参数」，
      combine 本身任一上游发射都会重跑 lambda —— **丢参数并不会阻止触发**。
      真正的缺陷是「输出被压缩成恒定值」再叠加去重：
        combine(...) { sessionId, _, _ -> sessionId }   // 输出恒等于 sessionId
            .distinctUntilChanged()                     // sessionId 未变 → 吞掉所有上游更新
      此时另外两个上游的任何变化都不会传导到下游。
      另一变体是 `{ x, _, _ -> x }.flatMapLatest { 一次性读取 }`：没有去重，
      上游仍会触发重跑，但每次都要重查数据源（性能问题而非正确性问题），
      故仅在同时出现 distinctUntilChanged 时判为缺陷。
    """
    findings = []
    for path in files:
        src = strip_comments(read(path))
        for m in re.finditer(r'combine\s*\(', src):
            start = m.end()
            depth, idx = 1, start
            while idx < len(src) and depth > 0:
                if src[idx] == '(':
                    depth += 1
                elif src[idx] == ')':
                    depth -= 1
                idx += 1
            args_region = src[start:idx - 1]
            depth2, commas = 0, 0
            for ch in args_region:
                if ch in '([{':
                    depth2 += 1
                elif ch in ')]}':
                    depth2 -= 1
                elif ch == ',' and depth2 == 0:
                    commas += 1
            stream_count = commas + 1
            if stream_count < 2:
                continue
            rest = src[idx:idx + 260]
            lam = re.match(
                r'\s*\)?\s*\{\s*([A-Za-z_][A-Za-z0-9_]*\s*(?:,\s*[A-Za-z_][A-Za-z0-9_]*\s*)*)->',
                rest)
            if not lam:
                continue
            params = [p.strip() for p in lam.group(1).split(',')]
            kept = [p for p in params if p != '_']
            dropped = [p for p in params if p == '_']
            if not dropped or len(kept) != 1:
                continue
            # 输出是否是「恒定值」：lambda 体恰为裸的 kept 参数（可能带 !!/?）
            body = rest[lam.end():lam.end() + 80]
            bare = re.match(r'\s*' + re.escape(kept[0]) + r'\s*[!?]?\s*\}', body)
            if not bare:
                continue
            # 紧随其后（跳过空白）是否为 distinctUntilChanged
            tail_region = src[idx + lam.end(): idx + lam.end() + 400]
            has_dedup = '.distinctUntilChanged()' in tail_region.replace(' ', '').replace('\n', '')
            if not has_dedup:
                continue
            line_no = src[:m.start()].count('\n') + 1
            findings.append({
                'rule': 'R3_combine_swallow_updates',
                'file': rel(root, path),
                'line': line_no,
                'streams': stream_count,
                'dropped_streams': len(dropped),
                'kept': kept[0],
                'severity': 'P0',
                'note': (f'combine 合并 {stream_count} 个流，但输出被压缩为恒定的 {kept[0]} 值，'
                         f'再经 distinctUntilChanged 去重：另外 {len(dropped)} 个流'
                         f'的任何变化都会被吞掉，相关 UI 只在切换 {kept[0]} 时才刷新'),
            })
    return findings


# ---------------------------------------------------------------- R4
def rule_default_value_divergence(root, files):
    """R4 同一偏好的默认值在多处硬编码不一致。"""
    findings = []
    # DataStore: `?: 128_000` / `getOrDefault(128_000)` / `?: true` 等
    pat = re.compile(r'\?\:\s*([0-9][0-9_]*|true|false)\b')
    by_key = defaultdict(list)
    for path in files:
        src = strip_comments(read(path))
        lines = src.split('\n')
        for i, line in enumerate(lines):
            if 'Preferences.data.map' not in line and 'Preferences.data' not in line:
                continue
            for m in pat.finditer(line):
                # 从该行提取偏好键名
                key = re.search(r'\[([A-Za-z_][A-Za-z0-9_]*)\]', line)
                if key:
                    by_key[key.group(1)].append((rel(root, path), i + 1, m.group(1)))
    for key, occ in sorted(by_key.items()):
        values = {v for _, _, v in occ}
        if len(values) > 1:
            findings.append({
                'rule': 'R4_default_value_divergence',
                'key': key,
                'values': sorted(values),
                'locations': [f'{f}:{ln}={v}' for f, ln, v in occ],
                'severity': 'P2',
                'note': f'偏好键 {key} 的默认值在多处不一致',
            })
    return findings


# ---------------------------------------------------------------- R5
def rule_orphan_preference_keys(root, files):
    """R5 偏好键被声明但无人读取（僵尸设置：UI 可调/可存，引擎不读）。"""
    findings = []
    store_file, keys = None, {}
    for path in files:
        if not path.endswith('SettingsDataStore.kt'):
            continue
        store_file = path
        src = strip_comments(read(path))
        for m in re.finditer(r'private val (\w+Key)\s*=\s*[\w.]*preferencesKey\("([^"]+)"\)', src):
            keys[m.group(1)] = m.group(2)
    if not keys:
        return findings
    all_src = '\n'.join(strip_comments(read(p)) for p in files if p != store_file)
    for var, key in sorted(keys.items()):
        # 这些 var 名通常形如 contextBudgetTokensKey；用「去掉 Key 后缀的名字」在 DataStore 外的引用判断
        stem = var[:-3] if var.endswith('Key') else var
        if stem not in all_src:
            findings.append({
                'rule': 'R5_orphan_preference_key',
                'key': key,
                'var': var,
                'severity': 'P1',
                'note': f'偏好键 "{key}"（{var}）在 SettingsDataStore 之外无引用，疑似僵尸设置',
            })
    return findings


RULES = [
    rule_duplicate_constants,
    rule_stateflow_distinct,
    rule_combine_swallow_updates,
    rule_default_value_divergence,
    rule_orphan_preference_keys,
]


def selftest():
    """自检：证明 P0 通路真的能亮，且退出码契约没被改坏。

    加这段的直接原因（真实事故）：CI 的门禁原本写成
        `grep -q 'P0_' /tmp/audit.log && { echo ...; exit 1; } || true`
    而本脚本从**不**输出 "P0_" 这个字面量（它打印的是 `[P0] R3_...`），
    于是那道自称"必须为 0"的门禁从上线起就从未拦截过任何提交。
    现在 CI 改用退出码判定；本自检把「P0 存在 → 退出码 1」锁成断言，
    任何人再改坏（改输出格式、改退出码、规则失效）都会被这里拦住。
    """
    import shutil
    import tempfile

    p0_probe = (
        'package probe\n'
        'class Probe {\n'
        '    fun build(a: StateFlow<String>, b: StateFlow<Int>, c: StateFlow<Int>) =\n'
        '        combine(a, b, c) { kept, _, _ ->\n'
        '            kept\n'
        '        }.distinctUntilChanged()\n'
        '}\n'
    )
    clean_probe = (
        'package probe\n'
        'class Clean {\n'
        '    fun plain() = listOf(1, 2, 3).sum()\n'
        '}\n'
    )

    def scan(src):
        d = tempfile.mkdtemp(dir=tmp)
        with open(os.path.join(d, 'Probe.kt'), 'w', encoding='utf-8') as fh:
            fh.write(src)
        files = list(iter_sources(d, include_tests=False))
        found = []
        for rule in RULES:
            try:
                found.extend(rule(d, files))
            except Exception as e:
                print(f'!! 规则 {rule.__name__} 执行失败: {e}', file=sys.stderr)
        return found

    def exit_code(findings):
        return 0 if not [f for f in findings if f['severity'] == 'P0'] else 1

    failures = []
    tmp = tempfile.mkdtemp(prefix='causal-selftest-')
    try:
        if exit_code(scan(p0_probe)) != 1:
            failures.append('含 combine-吞更新 的样本应返回 1（存在 P0），实际不是')
        if exit_code(scan(clean_probe)) != 0:
            failures.append('干净样本应返回 0，实际不是')
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if failures:
        for msg in failures:
            print(f'FAIL: {msg}', file=sys.stderr)
        return 1
    print('selftest OK：P0 通路可亮、干净输入退出码为 0')
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', default='.')
    ap.add_argument('--json', default='')
    ap.add_argument('--no-tests', action='store_true')
    ap.add_argument('--selftest', action='store_true',
                    help='自检：验证 P0 检测通路与退出码契约（忽略 --root）')
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    root = os.path.abspath(args.root)
    files = list(iter_sources(root, include_tests=not args.no_tests))
    print(f'扫描 {len(files)} 个 Kotlin 文件（根目录 {root}）\n')

    all_findings = []
    for rule in RULES:
        try:
            found = rule(root, files)
        except Exception as e:  # 单条规则出错不影响其他规则
            print(f'!! 规则 {rule.__name__} 执行失败: {e}', file=sys.stderr)
            continue
        all_findings.extend(found)

    order = {'P0': 0, 'P1': 1, 'P2': 2}
    all_findings.sort(key=lambda f: (order.get(f['severity'], 9), f['rule']))

    counts = defaultdict(int)
    for f in all_findings:
        counts[f['rule']] += 1

    print('=' * 78)
    print('因果链闭环审计结果')
    print('=' * 78)
    for f in all_findings:
        print(f"\n[{f['severity']}] {f['rule']}")
        for k, v in f.items():
            if k in ('rule', 'severity', 'note'):
                continue
            if isinstance(v, list):
                for item in v:
                    print(f'    {k}: {item}')
            else:
                print(f'    {k}: {v}')
        print(f"    说明: {f['note']}")

    print('\n' + '=' * 78)
    print('汇总')
    print('=' * 78)
    for rule, n in sorted(counts.items()):
        print(f'  {rule}: {n}')
    print(f'  合计: {len(all_findings)}')

    if args.json:
        with open(args.json, 'w', encoding='utf-8') as f:
            json.dump(all_findings, f, ensure_ascii=False, indent=2)
        print(f'\n已写出 JSON: {args.json}')

    return 0 if not [f for f in all_findings if f['severity'] == 'P0'] else 1


if __name__ == '__main__':
    sys.exit(main())
