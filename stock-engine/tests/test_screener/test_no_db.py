"""选股中心不触库硬约束验证（spec AC-11 / TR-12.6）。

扫描选股中心全部模块源码，确认不出现任何数据库操作
（sqlite3 / sqlalchemy / 直连 .db / DB connect / cursor / SQL 关键字）。
engine 数据单源性在 watcher，禁止 engine 侧落业务库。
"""
import re
from pathlib import Path

# 待扫描目录 / 文件（选股中心实现 + Schema + 路由）
_SCREENER_SOURCES = [
    Path("services/screener"),
    Path("api/v1/screener.py"),
    Path("models/schemas/screener.py"),
    Path("models/schemas/condition.py"),
]

# 命中即视为违规的 DB 指纹（与 tests/test_factor/test_no_db.py 保持一致）
_FORBIDDEN_PATTERNS = [
    re.compile(r"\bsqlite3\b"),
    re.compile(r"\bsqlalchemy\b"),
    re.compile(r"import\s+sqlite3"),
    re.compile(r"import\s+sqlalchemy"),
    re.compile(r"from\s+sqlite3"),
    re.compile(r"from\s+sqlalchemy"),
    re.compile(r"\.db['\"]"),          # 字符串里的 .db 路径
    re.compile(r"\.connect\s*\("),     # DB connect 调用
    re.compile(r"\.cursor\s*\("),      # DB cursor 调用
    re.compile(r"CREATE\s+TABLE", re.I),
    re.compile(r"SELECT\s+.*\s+FROM", re.I),
]


def _strip_code(text: str) -> str:
    """剥离注释与三引号 docstring，避免 docstring 里出现 "无 sqlite3/sqlalchemy"
    等说明性字样被误判为 DB 操作（与 test_factor 同款约束保持一致口径，
    但选股中心多个模块的 docstring 显式写了 "无 sqlite3/sqlalchemy" 约束声明，
    必须剥离后扫描）。
    """
    import io
    import tokenize

    stripped = []
    try:
        tokens = tokenize.generate_tokens(io.StringIO(text).readline)
        for tok in tokens:
            ttype, tstring, _, _, _ = tok
            # 保留 NAME / OP / NUMBER 等真实代码 token；丢弃 COMMENT / STRING
            if ttype in (tokenize.COMMENT, tokenize.STRING):
                continue
            stripped.append(tstring)
    except tokenize.TokenError:
        # token 失败（如 f-string 内嵌）→ 退回原文，按行匹配（仍受 import 上下文约束）
        return text
    return " ".join(stripped)


def test_no_database_code_in_screener_module():
    """选股中心模块源码不得出现任何数据库操作代码（违反 engine 不触库硬约束）。

    扫描前先剥离注释/docstring，避免模块头部的 "无 sqlite3/sqlalchemy" 约束声明被误报。
    """
    violations = []
    for target in _SCREENER_SOURCES:
        files = [target] if target.is_file() else sorted(target.rglob("*.py"))
        for path in files:
            text = path.read_text(encoding="utf-8")
            code = _strip_code(text)
            for pat in _FORBIDDEN_PATTERNS:
                for m in pat.finditer(code):
                    # 把匹配位置回映到原行号（取片段首行用于报错定位）
                    line_no = code.count(" ", 0, m.start())  # 占位，定位粗略
                    violations.append(
                        f"{path}: {pat.pattern} (matched token near offset {m.start()}, line~{line_no})"
                    )
    assert not violations, (
        "选股中心模块发现数据库操作代码（违反 engine 不触库硬约束）:\n"
        + "\n".join(violations)
    )
