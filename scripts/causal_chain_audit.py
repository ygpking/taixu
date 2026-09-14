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
    """R1 同名单字面量在多个文件定义且值不同（单一真相源缺失）。"""
    findings = []
    # 形如: (private )?const val NAME = 123  或  val NAME = 123
    pat = re.compile(
        r'^\s*(?:private\s+|internal\s+|public\s+)*const\s+val\s+([A-Z][A-Z0-9_]*)\s*[:=]\s*'
        r'(?:Int|Long|Float|Double)?\s*[=]?\s*([0-9][0-9_]*|[0-9_]*[0-9])\b',
        re.M)
    by_name = defaultdict(list)
    for path in files:
        src = strip_comments(read(path))
        for m in pat.finditer(src):
            name, value = m.group(1), m.group(2).replace('_', '')
            by_name[name].append((rel(root, path), value))
    for name, occurrences in sorted(by_name.items()):
        values = {v for _, v in occurrences}
        if len(values) > 1:
            findings.append({
                'rule': 'R1_duplicate_constant',
                'symbol': name,
                'values': sorted(values),
                'locations': [f'{f}:{v}' for f, v in occurrences],
                'severity': 'P1',
                'note': f'同名常量 {name} 在多处定义且取值不同，可能违反单一真相源',
            })
    return findings


# ---------------------------------------------------------------- R2
def rule_stateflow_distinct(root, files):
    """R2 对 StateFlow 调 distinctUntilChanged（本项目 allWarningsAsErrors → 编译失败）。"""
    findings = []
    for path in files:
        src = strip_comments(read(path))
        lines = src.split('\n')
        for i, line in enumerate(lines):
            if '.distinctUntilChanged()' not in line:
                continue
            # 往上找最近 12 行，看上游是否 StateFlow 类型的标识符
            window = lines[max(0, i - 12):i + 1]
            upstream = ' '.join(window)
            # 收线：本行/上一行直接以 StateFlow 变量结尾，或上游出现 ": StateFlow<" 声明
            tail = ' '.join(lines[max(0, i - 3):i + 1])
            direct = re.search(
                r'([A-Za-z_][A-Za-z0-9_]*)\s*(?:\.\s*value)?\s*$', tail.replace('.distinctUntilChanged()', ''))
            typed = re.findall(r'val\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*StateFlow<', upstream)
            suspect = None
            if direct and direct.group(1) in typed:
                suspect = direct.group(1)
            findings.append({
                'rule': 'R2_stateflow_distinct',
                'file': rel(root, path),
                'line': i + 1,
                'symbol': suspect or '(需人工确认上游类型)',
                'severity': 'P0' if suspect else 'P2',
                'note': ('对 StateFlow 调用 distinctUntilChanged：既无效果又会被当作 error，'
                         '必须删除' if suspect else
                         '存在 distinctUntilChanged，请人工确认上游是否为 StateFlow'),
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', default='.')
    ap.add_argument('--json', default='')
    ap.add_argument('--no-tests', action='store_true')
    args = ap.parse_args()

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
