"""engine → watcher 的只读内部接口客户端（spec 010-rotation-data-governance §5.1.2）。

仅限查询 watcher 的 /api/internal/* 只读端点（参考数据：成分股身份等），
不得用于行情/基本面拉取（强约束）。
"""
from __future__ import annotations

from typing import Optional

from core.logger import logger


class WatcherClient:
    """engine → watcher 的只读内部接口客户端。

    用法::

        client = WatcherClient("http://watcher:8080")
        eligible = client.get_constituents_at("000300.SH", "2022-06-15")
    """

    def __init__(self, base_url: str, timeout: float = 5.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def get_constituents_at(
        self, index_code: str, trade_date: str
    ) -> set[str]:
        """查询 point-in-time 成分股快照（≤ trade_date 的最新生效日）。

        :param index_code: 指数代码，如 "000300.SH" / "000905.SH"。
        :param trade_date: 调仓日（YYYY-MM-DD 或 YYYYMMDD 字符串）。
        :return: 该日实际入选成分股集合（空集=无快照或查询失败）。
        """
        # 延迟导入 requests，避免无网络环境 import 失败
        try:
            import requests
        except ImportError:
            logger.warning("WatcherClient: requests 库未安装，跳过成分股查询")
            return set()

        url = f"{self.base_url}/api/internal/constituents/query"
        try:
            resp = requests.post(
                url,
                json={"index_code": index_code, "trade_date": trade_date},
                timeout=self.timeout,
            )
            resp.raise_for_status()
            data = resp.json()
            # watcher 端用 ApiResponse 统一包装：{"code":200,"message":"success","data":{...}}
            # 兼容 ApiResponse 包装与裸返回两种形态
            payload = data.get("data", data) if isinstance(data, dict) else {}
            constituents = payload.get("constituents") or []
            effective_date = payload.get("effective_date")
            logger.info(
                "WatcherClient.get_constituents_at: index=%s date=%s effective=%s hit=%d",
                index_code, trade_date, effective_date, len(constituents),
            )
            return set(constituents)
        except Exception as exc:  # noqa: BLE001 - 查询失败降级为空集
            logger.warning(
                "WatcherClient 查询成分股失败 index=%s date=%s: %s",
                index_code, trade_date, exc,
            )
            return set()


def build_watcher_client(
    watcher_base_url: Optional[str], timeout: float = 5.0
) -> Optional[WatcherClient]:
    """根据 base_url 构造 WatcherClient；url 为空则返回 None（降级模式）。

    :param watcher_base_url: 从 HTTP header X-Watcher-Base-Url 或环境变量 WATCHER_BASE_URL 取。
        watcher 与 engine 同机部署，通常为 ``http://localhost:<port>``（端口可变）。
    :return: WatcherClient 实例或 None（未配置时降级）。
    """
    if not watcher_base_url:
        return None
    return WatcherClient(watcher_base_url, timeout=timeout)
