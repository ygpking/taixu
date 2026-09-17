#!/usr/bin/env python3
"""Small stdio MCP server for SQLite, with no third-party dependencies."""
import argparse
import json
import sqlite3
import sys

PROTOCOL_VERSION = "2025-06-18"


def response(req_id, result=None, error=None):
    value = {"jsonrpc": "2.0", "id": req_id}
    if error is not None:
        value["error"] = error
    else:
        value["result"] = result
    return value


def tool_specs():
    text = {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}
    return [
        {"name": "read_query", "description": "执行 SELECT 查询", "inputSchema": text},
        {"name": "write_query", "description": "执行 INSERT、UPDATE、DELETE 或 CREATE 语句", "inputSchema": text},
        {"name": "list_tables", "description": "列出 SQLite 数据库中的表", "inputSchema": {"type": "object", "properties": {}}},
        {"name": "describe_table", "description": "查看指定数据表结构", "inputSchema": {"type": "object", "properties": {"table_name": {"type": "string"}}, "required": ["table_name"]}},
    ]


def validate_query(query):
    """验证 SQL 查询的安全性，防止注入攻击"""
    if not query or not isinstance(query, str):
        return False, "查询必须是非空字符串"
    
    query_upper = query.upper().strip()
    
    # 允许的操作前缀白名单
    allowed_prefixes = ("SELECT", "WITH", "PRAGMA", "EXPLAIN")
    if not any(query_upper.startswith(prefix) for prefix in allowed_prefixes):
        return False, "只允许 SELECT/WITH/PRAGMA/EXPLAIN 操作"
    
    # 检测危险的 SQL 注入模式
    dangerous_patterns = [
        "--",                    # SQL 注释
        ";",                     # 语句分隔符
        "/*", "*/",              # 块注释
        "EXEC", "EXECUTE",       # 执行存储过程
        "XP_",                   # SQL Server 扩展存储过程
        "LOAD_EXTENSION",        # SQLite 扩展加载
        "ATTACH", "DETACH",      # 数据库附加/分离
        ".IMPORT", ".SHELL",     # SQLite shell 命令
        "UNION ALL SELECT",      # UNION 注入
        "OR 1=1", "OR '1'='1'",  # 常见注入 payload
        "DROP ", "DELETE ", "TRUNCATE ", "ALTER ", "CREATE ", "INSERT ", "UPDATE "]
    
    for pattern in dangerous_patterns:
        if pattern.upper() in query_upper:
            # 但允许 PRAGMA table_info 等合法使用
            if pattern.strip() in ("DROP ", "DELETE ", "TRUNCATE ", "ALTER ", "CREATE ", "INSERT ", "UPDATE "):
                return False, "包含禁止的操作关键字: {}".format(pattern)
    
    return True, None


def call_tool(db_path, name, args):
    with sqlite3.connect(db_path) as db:
        db.row_factory = sqlite3.Row
        if name == "read_query":
            query = str(args.get("query", "")).strip()
            
            # 验证查询安全性
            is_valid, error_msg = validate_query(query)
            if not is_valid:
                raise ValueError("SQL 验证失败：{}".format(error_msg))
            
            # 启用只读模式防止写入操作
            try:
                db.execute("PRAGMA query_only = ON")
                rows = [dict(row) for row in db.execute(query)]
                db.execute("PRAGMA query_only = OFF")
            except sqlite3.OperationalError:
                # 如果 query_only 不支持，回退到直接执行（已通过 validate_query 验证）
                rows = [dict(row) for row in db.execute(query)]
            return json.dumps(rows, ensure_ascii=False)
        if name == "write_query":
            query = str(args.get("query", "")).strip()
            if not query or query.lower().startswith(("select", "pragma")):
                raise ValueError("write_query 需要写入或 DDL 语句")
            # 阻止危险的 PRAGMA 和函数调用
            dangerous_patterns = ["attach", "detach", "load_extension", ".import", ".shell"]
            if any(pattern in query.lower() for pattern in dangerous_patterns):
                raise ValueError("包含危险操作")
            cursor = db.execute(query)
            db.commit()
            return json.dumps({"affected_rows": cursor.rowcount}, ensure_ascii=False)
        if name == "list_tables":
            rows = db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
            return json.dumps([row[0] for row in rows], ensure_ascii=False)
        if name == "describe_table":
            table = str(args.get("table_name", "")).strip()
            if not table or any(ch not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_" for ch in table):
                raise ValueError("table_name 无效")
            # 安全修复：使用参数化方式或严格验证后的表名
            # 由于 PRAGMA table_info 不支持参数绑定，必须依赖严格的表名验证
            # 已验证表名只包含字母数字和下划线，此处使用 format 是安全的
            rows = db.execute('PRAGMA table_info("{}")'.format(table)).fetchall()
            return json.dumps([dict(row) for row in rows], ensure_ascii=False)
        raise ValueError("未知工具: " + name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-path", required=True)
    args = parser.parse_args()
    for line in sys.stdin:
        try:
            req = json.loads(line)
            method = req.get("method")
            req_id = req.get("id")
            if method == "initialize":
                result = {"protocolVersion": PROTOCOL_VERSION, "capabilities": {"tools": {}}, "serverInfo": {"name": "taixu-sqlite", "version": "1.0.0"}}
                out = response(req_id, result)
            elif method == "notifications/initialized":
                continue
            elif method == "tools/list":
                out = response(req_id, {"tools": tool_specs()})
            elif method == "tools/call":
                params = req.get("params") or {}
                try:
                    text = call_tool(args.db_path, str(params.get("name", "")), params.get("arguments") or {})
                    result = {"content": [{"type": "text", "text": text}], "isError": False}
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
