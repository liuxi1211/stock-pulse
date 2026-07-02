"""因子注册中心：定义加载 / 持久化 / CRUD（spec FR-1, FR-8, NFR-2, NFR-4）。

- 启动加载 ``data/factors.json``；缺失或损坏从种子 ``data/factors.default.json`` 恢复并告警。
- 内存是唯一读写入口，文件是持久化镜像；每次 CRUD 后原子写入。
- 原子写入：临时文件 + ``os.replace``，防半写损坏（NFR-2 / AC-17）。
- 写操作用 ``threading.Lock`` 串行化（NFR-4）；读操作无锁，写期间读返回旧值（最终一致）。
"""
import ast
import json
import operator as _op
import os
import tempfile
import threading
from typing import Optional

from config import settings
from core.exceptions import (
    FactorAlreadyExistsError,
    FactorNotFoundError,
    ValidationException,
)
from core.logger import logger
from models.schemas.factor import FactorDef

# 预定义分类 key（校验用）—— 与种子 JSON 的 categories 对齐
_VALID_CATEGORIES = {
    "OVERLAP", "MOMENTUM", "VOLATILITY", "VOLUME", "STATISTIC",
    "PRICE", "VALUATION", "QUALITY", "GROWTH", "FINANCE",
}
_VALID_SOURCES = {"AKQUANT", "TUSHARE", "RAW", "DERIVED"}

# lookbackHint 仅允许的算术运算（安全求值，lookbackHint 可能来自用户 CRUD 输入）
_ALLOWED_BINOPS = {
    ast.Add: _op.add, ast.Sub: _op.sub, ast.Mult: _op.mul,
    ast.Div: _op.truediv, ast.FloorDiv: _op.floordiv, ast.Mod: _op.mod,
}
_ALLOWED_UNARYOPS = {ast.UAdd: _op.pos, ast.USub: _op.neg}


def _safe_eval_arith(expr: str, names: dict) -> int:
    """安全求值纯算术表达式（如 ``timeperiod - 1``、``2 * timeperiod - 1``）。

    仅允许数字字面量、变量名（取自 names）与加减乘除；禁止属性访问、调用、
    字符串、builtins，规避任意代码执行。
    """
    tree = ast.parse(expr, mode="eval")

    def _eval(node):
        if isinstance(node, ast.Expression):
            return _eval(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
            return node.value
        if isinstance(node, ast.Name):
            if node.id not in names:
                raise ValueError(f"未知变量 {node.id}")
            return names[node.id]
        if isinstance(node, ast.BinOp) and type(node.op) in _ALLOWED_BINOPS:
            return _ALLOWED_BINOPS[type(node.op)](_eval(node.left), _eval(node.right))
        if isinstance(node, ast.UnaryOp) and type(node.op) in _ALLOWED_UNARYOPS:
            return _ALLOWED_UNARYOPS[type(node.op)](_eval(node.operand))
        raise ValueError(f"不支持的表达式节点: {ast.dump(node)}")

    return int(_eval(tree.body))


class FactorRegistry:
    """因子注册中心（模块级单例 ``factor_registry``）。"""

    def __init__(self, runtime_file: Optional[str] = None, seed_file: Optional[str] = None):
        self._runtime_file = runtime_file or settings.factors_runtime_file
        self._seed_file = seed_file or settings.factors_seed_file
        self._lock = threading.Lock()
        self._factors: dict[str, FactorDef] = {}   # factorKey -> FactorDef（内存真相源）
        self._categories: list[dict] = []
        self.reload()

    # ------------------------------------------------------------------
    # 加载 / 持久化
    # ------------------------------------------------------------------

    def reload(self) -> None:
        """启动加载：优先运行时文件，缺失/损坏则从种子恢复并重建运行时文件。"""
        data = self._load_json(self._runtime_file)
        if data is None:
            logger.warning("因子运行时文件缺失或损坏，从种子恢复: %s", self._runtime_file)
            data = self._load_json(self._seed_file)
            if data is None:
                raise RuntimeError(f"种子文件也无法读取: {self._seed_file}")
            self._dump_json_atomic(data, self._runtime_file)

        self._categories = data.get("categories", [])
        factors = {}
        for raw in data.get("factors", []):
            try:
                fd = FactorDef(**raw)
                factors[fd.factorKey] = fd
            except Exception as exc:  # 跳过单条非法定义，不阻断启动
                logger.error("跳过非法因子定义 %s: %s", raw.get("factorKey"), exc)
        self._factors = factors
        logger.info("FactorRegistry 已加载 %d 个因子 / %d 个分类", len(factors), len(self._categories))

    @staticmethod
    def _load_json(path: str) -> Optional[dict]:
        try:
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
        except FileNotFoundError:
            return None
        except (json.JSONDecodeError, OSError) as exc:
            logger.error("读取 JSON 失败 %s: %s", path, exc)
            return None

    def _dump_json_atomic(self, data: dict, path: str) -> None:
        """原子写入：临时文件 + ``os.replace``（NFR-2 / AC-17）。"""
        directory = os.path.dirname(os.path.abspath(path)) or "."
        os.makedirs(directory, exist_ok=True)
        # 临时文件落在同目录，保证 os.replace 原子（同文件系统）
        fd, tmp_path = tempfile.mkstemp(suffix=".tmp", prefix="factors.", dir=directory, text=True)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            os.replace(tmp_path, path)
        except Exception:
            try:
                os.remove(tmp_path)  # 清理半写临时文件
            except OSError:
                pass
            raise

    def _persist(self) -> None:
        """内存镜像落盘（调用方需持 ``_lock``）。"""
        payload = {
            "version": "2.0",
            "description": "StockPulse 标准因子库（运行时）",
            "categories": self._categories,
            "factors": [fd.model_dump() for fd in self._factors.values()],
        }
        self._dump_json_atomic(payload, self._runtime_file)

    # ------------------------------------------------------------------
    # 校验
    # ------------------------------------------------------------------

    @staticmethod
    def _validate(fd: FactorDef) -> None:
        if fd.source not in _VALID_SOURCES:
            raise ValidationException(f"非法 source: {fd.source}")
        if fd.category not in _VALID_CATEGORIES:
            raise ValidationException(f"非法 category: {fd.category}")
        if fd.source in ("AKQUANT", "DERIVED") and not fd.akquantFunc:
            raise ValidationException(f"{fd.source} 来源因子必须提供 akquantFunc")
        if fd.source == "TUSHARE" and not fd.tushareField:
            raise ValidationException("TUSHARE 来源因子必须提供 tushareField")
        for p in fd.params:
            if p.max < p.min:
                raise ValidationException(f"参数 {p.name} max 小于 min")
            if not (p.min <= p.defaultValue <= p.max):
                raise ValidationException(f"参数 {p.name} defaultValue 不在 [min, max] 内")
        if fd.multiOutput and not (0 <= fd.defaultOutputIndex < len(fd.outputLabels)):
            raise ValidationException("multiOutput 因子的 defaultOutputIndex 越界")

    # ------------------------------------------------------------------
    # 读（无锁，天然并发安全）
    # ------------------------------------------------------------------

    def get_factor(self, factor_key: str) -> FactorDef:
        fd = self._factors.get(factor_key)
        if fd is None:
            raise FactorNotFoundError(f"因子不存在: {factor_key}")
        return fd

    def list_factors(
        self, category: Optional[str] = None, source: Optional[str] = None
    ) -> list[FactorDef]:
        result = list(self._factors.values())
        if category:
            result = [f for f in result if f.category == category]
        if source:
            result = [f for f in result if f.source == source]
        return result

    def list_categories(self) -> list[dict]:
        return list(self._categories)

    def exists(self, factor_key: str) -> bool:
        return factor_key in self._factors

    # ------------------------------------------------------------------
    # 写（持锁串行化）
    # ------------------------------------------------------------------

    def add_factor(self, fd: FactorDef) -> FactorDef:
        with self._lock:
            if fd.factorKey in self._factors:
                raise FactorAlreadyExistsError(f"因子已存在: {fd.factorKey}")
            self._validate(fd)
            self._factors[fd.factorKey] = fd
            self._persist()
            logger.info("新增因子 %s", fd.factorKey)
            return fd

    def update_factor(self, factor_key: str, updates: dict) -> FactorDef:
        with self._lock:
            if factor_key not in self._factors:
                raise FactorNotFoundError(f"因子不存在: {factor_key}")
            merged = self._factors[factor_key].model_dump()
            for k, v in updates.items():
                if v is not None:
                    merged[k] = v
            fd = FactorDef(**merged)
            self._validate(fd)
            self._factors[factor_key] = fd
            self._persist()
            logger.info("修改因子 %s", factor_key)
            return fd

    def delete_factor(self, factor_key: str) -> None:
        with self._lock:
            if factor_key not in self._factors:
                raise FactorNotFoundError(f"因子不存在: {factor_key}")
            del self._factors[factor_key]
            self._persist()
            logger.info("删除因子 %s", factor_key)

    # ------------------------------------------------------------------
    # 回看长度（spec FR-8）
    # ------------------------------------------------------------------

    def get_lookback(self, factor_key: str, params: Optional[dict] = None) -> int:
        """基于 ``lookbackHint`` 表达式 + 参数计算所需回看 bars。

        表达式如 ``timeperiod - 1``，仅做受限算术；求值失败回退 ``lookbackDefault``。
        """
        fd = self.get_factor(factor_key)
        names = {p.name: p.defaultValue for p in fd.params}
        if params:
            names.update({k: v for k, v in params.items() if v is not None})
        try:
            return max(0, _safe_eval_arith(fd.lookbackHint, names))
        except Exception:
            return max(0, fd.lookbackDefault)


# 模块级单例（engine 运行时使用；测试可另建 FactorRegistry 指向临时文件）
factor_registry = FactorRegistry()
