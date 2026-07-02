"""因子计算服务层：Provider 路由 + 参数校验 + 批量计算（spec FR-3~8, AC-9~11）。

- 按 ``source`` 路由到对应 Provider（AKQUANT/RAW/DERIVED）；TUSHARE 抛 ``FactorNotComputableError``。
- 参数校验：未知参数名 / 越界 min-max → ``InvalidParamError``。
- 多输出降维：``output_index``（None→defaultOutputIndex），越界 → ``InvalidOutputIndexError``。
- 未知 factorKey（计算场景）→ ``UnknownFactorError``（区别于 CRUD 的 FACTOR_NOT_FOUND）。
"""
from typing import Any, Optional, Union

import numpy as np

from core.exceptions import (
    FactorNotComputableError,
    InvalidOutputIndexError,
    InvalidParamError,
    UnknownFactorError,
)
from models.schemas.factor import FactorDef
from services.factor.providers import (
    AkquantTalibProvider,
    DerivedProvider,
    RawDataProvider,
)
from services.factor.registry import FactorRegistry, factor_registry

NDArrayTuple = tuple[np.ndarray, ...]


class FactorCalculatorService:
    """统一对外因子计算服务（模块级实例 ``factor_calculator``）。"""

    def __init__(self, registry: Optional[FactorRegistry] = None) -> None:
        self._registry = registry or factor_registry
        # TUSHARE 不注册 provider，compute 时直接拒绝
        self._providers = {
            "AKQUANT": AkquantTalibProvider(),
            "RAW": RawDataProvider(),
            "DERIVED": DerivedProvider(),
        }

    # ------------------------------------------------------------------
    # 内部：参数解析 / 路由 / 输出选择
    # ------------------------------------------------------------------

    def _resolve_params(self, fd: FactorDef, params: Optional[dict[str, Any]]) -> dict[str, Any]:
        """合并默认值 + 校验用户传入参数（名称、数值、范围）。"""
        merged = {p.name: p.defaultValue for p in fd.params}
        if not params:
            return merged
        for key, value in params.items():
            if value is None:
                continue
            pdef = next((p for p in fd.params if p.name == key), None)
            if pdef is None:
                raise InvalidParamError(f"因子 {fd.factorKey} 不支持参数 {key}")
            try:
                value_num = float(value)
            except (TypeError, ValueError) as exc:
                raise InvalidParamError(f"参数 {key} 非数值: {value}") from exc
            if value_num < pdef.min or value_num > pdef.max:
                raise InvalidParamError(
                    f"参数 {key}={value_num} 超出范围 [{pdef.min}, {pdef.max}]"
                )
            merged[key] = value_num
        return merged

    def _route_compute(
        self, fd: FactorDef, inputs: dict[str, np.ndarray], params: dict[str, Any]
    ) -> Union[np.ndarray, NDArrayTuple]:
        if fd.source == "TUSHARE":
            raise FactorNotComputableError(
                f"基本面因子 {fd.factorKey} 不可由 engine 计算，由 watcher 侧提供"
            )
        provider = self._providers.get(fd.source)
        if provider is None:
            raise UnknownFactorError(f"不支持的因子来源: {fd.source}")
        # AKQUANT/DERIVED 用 akquantFunc；RAW 为 None（provider 按 input_cols 直通）
        return provider.compute(fd.akquantFunc, inputs, list(fd.inputs), params)

    @staticmethod
    def _select_output(
        fd: FactorDef, result: Union[np.ndarray, NDArrayTuple], output_index: Optional[int]
    ) -> np.ndarray:
        """按 output_index 降维（None→defaultOutputIndex）。"""
        if isinstance(result, tuple):
            count = len(result)
            idx = fd.defaultOutputIndex if output_index is None else output_index
            if idx < 0 or idx >= count:
                raise InvalidOutputIndexError(
                    f"{fd.factorKey} 共 {count} 路输出，output_index={idx} 越界"
                )
            return result[idx]
        # 单输出因子：仅接受 None 或 0
        if output_index not in (None, 0):
            raise InvalidOutputIndexError(
                f"{fd.factorKey} 为单输出，output_index={output_index} 越界"
            )
        return result

    # ------------------------------------------------------------------
    # 对外：单因子 / 批量 / 多标的
    # ------------------------------------------------------------------

    def compute_single(
        self,
        factor_key: str,
        inputs: dict[str, np.ndarray],
        params: Optional[dict[str, Any]] = None,
        output_index: Optional[int] = None,
    ) -> np.ndarray:
        """计算单个因子单路输出（已按 output_index 降维）。"""
        if not self._registry.exists(factor_key):
            raise UnknownFactorError(f"未知的因子: {factor_key}")
        fd = self._registry.get_factor(factor_key)
        merged = self._resolve_params(fd, params)
        result = self._route_compute(fd, inputs, merged)
        return self._select_output(fd, result, output_index)

    def compute_batch(
        self,
        factor_list: list[dict[str, Any]],
        inputs: dict[str, np.ndarray],
    ) -> dict[str, np.ndarray]:
        """同一份输入数据，计算多个因子 → {factorKey: ndarray}。"""
        result: dict[str, np.ndarray] = {}
        for spec in factor_list:
            key = spec["factorKey"]
            result[key] = self.compute_single(
                key, inputs, spec.get("params"), spec.get("outputIndex")
            )
        return result

    def compute_multi_symbol(
        self,
        symbols_data: dict[str, dict[str, np.ndarray]],
        factor_list: list[dict[str, Any]],
    ) -> dict[str, dict[str, np.ndarray]]:
        """多标的批量计算 → {symbol: {factorKey: ndarray}}。"""
        return {
            symbol: self.compute_batch(factor_list, arr_inputs)
            for symbol, arr_inputs in symbols_data.items()
        }


# 模块级实例（engine 运行时使用）
factor_calculator = FactorCalculatorService()
