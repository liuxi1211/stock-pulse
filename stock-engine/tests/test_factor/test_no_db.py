"""不触库硬约束验证（spec AC-12 / TR-12.1）。

扫描因子库模块源码，确认不出现任何数据库操作（sqlite3 / sqlalchemy / 直连 .db）。
engine 数据单源性在 watcher，禁止 engine 侧落业务库。
"""
import re
from pathlib import Path

# 待扫描目录 / 文件
_FACTOR_SOURCES = [
    Path("services/factor"),
    Path("api/v1/factor.py"),
    Path("models/schemas/factor.py"),
]

# 命中即视为违规的 DB 指纹（精确，避免误报）
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


def test_no_database_code_in_factor_module():
    violations = []
    for target in _FACTOR_SOURCES:
        files = [target] if target.is_file() else sorted(target.rglob("*.py"))
        for path in files:
            text = path.read_text(encoding="utf-8")
            for pat in _FORBIDDEN_PATTERNS:
                for m in pat.finditer(text):
                    line_no = text.count("\n", 0, m.start()) + 1
                    snippet = text.splitlines()[line_no - 1].strip()
                    violations.append(f"{path}:{line_no}: {pat.pattern} -> {snippet}")
    assert not violations, "因子库模块发现数据库操作代码（违反 engine 不触库硬约束）:\n" + "\n".join(violations)
