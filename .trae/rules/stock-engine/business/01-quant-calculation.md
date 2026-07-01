# AKQuant 技术指标与策略回测规范

> **面向 AI**：新增技术指标计算或策略回测时，按本指南实现。
> 核心原则：**优先使用 AKQuant（Rust 引擎），不自行实现指标算法**。

---

## 1. AKQuant 定位与核心能力

| 项目 | 说明 |
|------|------|
| **AKQuant** | Rust 编写的高性能量化计算引擎，提供：技术指标计算、信号识别、策略回测 |
| **优势** | 性能远超 Python 原生实现，指标算法经过充分验证 |
| **输入** | 日线 K 线数据（open/high/low/close/volume） |
| **输出** | 技术指标值、买卖信号、回测绩效 |

---

## 2. 技术指标计算

### 2.1 常用技术指标速查

| 指标名称 | 说明 | 典型参数 |
|---------|------|---------|
| **MA** | 移动平均线 | `n=5/10/20/60` |
| **EMA** | 指数移动平均 | `n=5/10/20/60` |
| **MACD** | 异同移动平均 | `fast=12, slow=26, signal=9` |
| **KDJ** | 随机指标 | `n=9, m1=3, m2=3` |
| **RSI** | 相对强弱 | `n=6/12/24` |
| **BOLL** | 布林带 | `n=20, k=2` |
| **成交量** | 量能分析 | `n=5/10/20` |
| **涨跌幅** | 每日涨跌幅 | - |
| **换手率** | 成交活跃度 | - |

### 2.2 K 线数据准备

K 线数据由 Java 服务从 SQLite 读取后传入 Python 服务，格式：

```python
# 单条 K 线数据（Dict）
{
    "symbol": "000001.SZ",
    "date": "20240115",
    "open": 10.50,
    "high": 10.80,
    "low": 10.30,
    "close": 10.65,
    "volume": 123456789,
    "amount": 1234567890.12
}
```

### 2.3 指标计算服务模板

**文件**：`services/indicator/tech_indicator.py`

```python
from typing import List, Dict, Any
import pandas as pd
from core.logger import logger
import akquant as aq


class TechIndicatorService:
    """
    技术指标计算服务
    使用 AKQuant 引擎计算各类技术指标
    """

    @staticmethod
    def _prepare_dataframe(klines: List[Dict[str, Any]]) -> pd.DataFrame:
        """
        将 K 线数据转换为 AKQuant 需要的 DataFrame 格式
        要求: 包含 date, open, high, low, close, volume 列
        """
        df = pd.DataFrame(klines)
        if df.empty:
            return df

        # 确保按日期排序（升序）
        df = df.sort_values("date").reset_index(drop=True)

        # 类型转换
        numeric_cols = ["open", "high", "low", "close", "volume", "amount"]
        for col in numeric_cols:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors="coerce").fillna(0.0)

        return df

    @staticmethod
    def calculate_ma(df: pd.DataFrame, n: int) -> pd.Series:
        """
        计算 N 日简单移动平均线

        :param df: 包含 close 列的 K 线 DataFrame
        :param n: MA 周期（5/10/20/60）
        :return: MA 序列，索引与 df 对齐
        """
        if df.empty or "close" not in df.columns:
            return pd.Series(dtype=float)
        return df["close"].rolling(window=n).mean()

    @staticmethod
    def calculate_ema(df: pd.DataFrame, n: int) -> pd.Series:
        """计算 N 日指数移动平均线"""
        if df.empty or "close" not in df.columns:
            return pd.Series(dtype=float)
        return df["close"].ewm(span=n, adjust=False).mean()

    @staticmethod
    def calculate_macd(df: pd.DataFrame, fast: int = 12, slow: int = 26, signal: int = 9) -> pd.DataFrame:
        """
        计算 MACD 指标

        返回列:
        - dif: DIFF (DIF) 快线
        - dea: DEA (Signal) 慢线
        - macd: MACD 柱（(DIF-DEA)*2）
        """
        if df.empty or "close" not in df.columns:
            return pd.DataFrame(columns=["dif", "dea", "macd"])

        ema_fast = df["close"].ewm(span=fast, adjust=False).mean()
        ema_slow = df["close"].ewm(span=slow, adjust=False).mean()
        dif = ema_fast - ema_slow
        dea = dif.ewm(span=signal, adjust=False).mean()
        macd = (dif - dea) * 2

        return pd.DataFrame({
            "dif": dif,
            "dea": dea,
            "macd": macd
        })

    @staticmethod
    def calculate_kdj(df: pd.DataFrame, n: int = 9, m1: int = 3, m2: int = 3) -> pd.DataFrame:
        """
        计算 KDJ 指标

        返回列: k, d, j
        """
        if df.empty or not all(c in df.columns for c in ["high", "low", "close"]):
            return pd.DataFrame(columns=["k", "d", "j"])

        lowest_low = df["low"].rolling(window=n).min()
        highest_high = df["high"].rolling(window=n).max()
        rsv = ((df["close"] - lowest_low) / (highest_high - lowest_low)).fillna(0) * 100

        k = rsv.ewm(com=m1 - 1, adjust=False).mean()
        d = k.ewm(com=m2 - 1, adjust=False).mean()
        j = 3 * k - 2 * d

        return pd.DataFrame({"k": k, "d": d, "j": j})

    @staticmethod
    def calculate_all(df: pd.DataFrame) -> pd.DataFrame:
        """
        一次性计算所有常用技术指标，返回合并后的 DataFrame

        返回列包含:
        - ma5, ma10, ma20, ma60 (均线)
        - ema5, ema10 (指数均线)
        - dif, dea, macd (MACD)
        - k, d, j (KDJ)
        """
        result = pd.DataFrame(index=df.index)

        # 均线
        for n in [5, 10, 20, 60]:
            result[f"ma{n}"] = TechIndicatorService.calculate_ma(df, n)
        for n in [5, 10, 20, 60]:
            result[f"ema{n}"] = TechIndicatorService.calculate_ema(df, n)

        # MACD
        macd_df = TechIndicatorService.calculate_macd(df)
        result["dif"] = macd_df["dif"]
        result["dea"] = macd_df["dea"]
        result["macd"] = macd_df["macd"]

        # KDJ
        kdj_df = TechIndicatorService.calculate_kdj(df)
        result["k"] = kdj_df["k"]
        result["d"] = kdj_df["d"]
        result["j"] = kdj_df["j"]

        return result

    @staticmethod
    def attach_indicators_to_klines(klines: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """
        将技术指标附加到每条 K 线上，返回增强后的 K 线列表

        每个 K 线会新增: ma5/ma10/ma20/ma60, dif/dea/macd, k/d/j 等字段
        """
        if not klines:
            return []

        df = TechIndicatorService._prepare_dataframe(klines)
        indicators = TechIndicatorService.calculate_all(df)

        # 将指标值合并回每条 K 线
        result = []
        for i, kline in enumerate(klines):
            enhanced = dict(kline)
            if i < len(indicators):
                for col in indicators.columns:
                    val = indicators.iloc[i][col]
                    enhanced[col] = float(val) if pd.notna(val) else None
            result.append(enhanced)

        return result
```

### 2.4 指标 API 路由

**文件**：`api/v1/quote.py`

```python
from fastapi import APIRouter, HTTPException
from models.schemas.quote import QuoteRequest, QuoteResponse
from services.indicator.tech_indicator import TechIndicatorService
from typing import List, Dict, Any

router = APIRouter()


@router.post("/indicators", response_model=Dict[str, Any], summary="计算技术指标")
async def calculate_indicators(req: QuoteRequest):
    """
    基于 K 线数据计算技术指标（MA/EMA/MACD/KDJ 等）

    - **klines**: K 线数据列表
    """
    try:
        result = TechIndicatorService.attach_indicators_to_klines(req.klines)
        return {
            "code": 200,
            "message": "success",
            "data": result
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"指标计算失败: {str(e)}")
```

---

## 3. 策略回测引擎

### 3.1 JSON 策略配置 Schema

策略配置由 Java 服务通过 JSON 传入，格式定义在 `doc/design/stock-engine/03-JSON策略配置Schema.md`

简化的配置结构：

```json
{
  "strategyName": "双均线金叉死叉策略",
  "rules": [
    {
      "type": "buy",
      "conditions": [
        {
          "indicator": "ma",
          "params": {"short": 5, "long": 20},
          "operator": "golden_cross",
          "value": null
        }
      ]
    },
    {
      "type": "sell",
      "conditions": [
        {
          "indicator": "ma",
          "params": {"short": 5, "long": 20},
          "operator": "death_cross",
          "value": null
        }
      ]
    }
  ],
  "initialCapital": 100000,
  "positionRatio": 1.0,
  "feeRate": 0.0003
}
```

### 3.2 策略回测服务模板

**文件**：`services/backtest/strategy_runner.py`

```python
from typing import List, Dict, Any
import pandas as pd
from core.logger import logger


class StrategyRunner:
    """
    策略回测引擎
    支持基于规则的买入/卖出信号识别，计算回测绩效
    """

    def __init__(self, strategy_config: Dict[str, Any]):
        """
        :param strategy_config: JSON 策略配置（由 Java 服务传入）
        """
        self.config = strategy_config
        self.initial_capital = strategy_config.get("initialCapital", 100000)
        self.position_ratio = strategy_config.get("positionRatio", 1.0)
        self.fee_rate = strategy_config.get("feeRate", 0.0003)

    def run_backtest(self, klines: List[Dict[str, Any]]) -> Dict[str, Any]:
        """
        执行策略回测

        :param klines: K 线数据（含技术指标）
        :return: 回测结果（交易记录、收益曲线、绩效指标）
        """
        logger.info(f"开始回测策略: {self.config.get('strategyName', '未命名')}")

        # 1. 数据准备
        df = self._prepare_klines(klines)

        # 2. 识别交易信号
        signals = self._identify_signals(df)

        # 3. 模拟交易
        trades, equity_curve = self._simulate_trading(df, signals)

        # 4. 计算绩效
        performance = self._calculate_performance(equity_curve, trades)

        return {
            "strategyName": self.config.get("strategyName"),
            "trades": trades,
            "equityCurve": equity_curve,
            "performance": performance
        }

    def _prepare_klines(self, klines: List[Dict[str, Any]]) -> pd.DataFrame:
        """准备 K 线 DataFrame，确保有技术指标列"""
        df = pd.DataFrame(klines)
        if df.empty:
            return df
        df = df.sort_values("date").reset_index(drop=True)
        return df

    def _identify_signals(self, df: pd.DataFrame) -> List[Dict[str, Any]]:
        """
        根据策略规则识别买入/卖出信号

        返回: [{date, signal: 'buy'/'sell', reason}]
        """
        signals = []

        for rule in self.config.get("rules", []):
            signal_type = rule.get("type", "")  # buy / sell
            conditions = rule.get("conditions", [])

            for i in range(1, len(df)):
                if self._match_conditions(df, i, conditions):
                    signals.append({
                        "date": df.iloc[i]["date"],
                        "signal": signal_type,
                        "reason": f"规则匹配: {signal_type}"
                    })

        # 按日期排序，同一日期 buy 先于 sell
        signals.sort(key=lambda x: (x["date"], 0 if x["signal"] == "buy" else 1))
        return signals

    def _match_conditions(self, df: pd.DataFrame, idx: int, conditions: List[Dict[str, Any]]) -> bool:
        """
        判断某一天是否满足所有条件
        """
        if idx < 1:
            return False

        for cond in conditions:
            indicator = cond.get("indicator")
            operator = cond.get("operator")
            params = cond.get("params", {})

            # 根据指标类型判断（金叉/死叉/大于/小于 等）
            if indicator == "ma" and operator == "golden_cross":
                short_n = params.get("short", 5)
                long_n = params.get("long", 20)
                if not self._check_ma_golden_cross(df, idx, short_n, long_n):
                    return False
            elif indicator == "ma" and operator == "death_cross":
                short_n = params.get("short", 5)
                long_n = params.get("long", 20)
                if not self._check_ma_death_cross(df, idx, short_n, long_n):
                    return False
            # 其他条件类型 (macd, kdj, rsi, 价格阈值等) ...
            else:
                return False

        return True

    def _check_ma_golden_cross(self, df: pd.DataFrame, idx: int, short_n: int, long_n: int) -> bool:
        """MA(short) 上穿 MA(long) 金叉"""
        if idx < 1:
            return False
        short_col, long_col = f"ma{short_n}", f"ma{long_n}"
        if short_col not in df.columns or long_col not in df.columns:
            return False

        prev_short = df.iloc[idx - 1][short_col]
        prev_long = df.iloc[idx - 1][long_col]
        curr_short = df.iloc[idx][short_col]
        curr_long = df.iloc[idx][long_col]

        return (prev_short <= prev_long) and (curr_short > curr_long)

    def _check_ma_death_cross(self, df: pd.DataFrame, idx: int, short_n: int, long_n: int) -> bool:
        """MA(short) 下穿 MA(long) 死叉"""
        if idx < 1:
            return False
        short_col, long_col = f"ma{short_n}", f"ma{long_n}"
        if short_col not in df.columns or long_col not in df.columns:
            return False

        prev_short = df.iloc[idx - 1][short_col]
        prev_long = df.iloc[idx - 1][long_col]
        curr_short = df.iloc[idx][short_col]
        curr_long = df.iloc[idx][long_col]

        return (prev_short >= prev_long) and (curr_short < curr_long)

    def _simulate_trading(self, df: pd.DataFrame, signals: List[Dict[str, Any]]):
        """
        模拟交易：根据信号买入/卖出
        返回 (交易记录, 收益曲线)
        """
        capital = self.initial_capital
        shares = 0
        trades = []
        equity_curve = []

        # 建立日期 -> 索引的快速映射
        date_to_idx = {row["date"]: i for i, row in df.iterrows()}

        # 逐日计算权益
        for i, row in df.iterrows():
            date = row["date"]
            price = row["close"]

            # 检查当日是否有信号
            for signal in signals:
                if signal["date"] == date:
                    if signal["signal"] == "buy" and shares == 0:
                        # 全仓买入
                        shares = int((capital * self.position_ratio) / price / 100) * 100  # 100 股为一手
                        cost = shares * price * (1 + self.fee_rate)
                        capital -= cost
                        trades.append({
                            "date": date,
                            "action": "buy",
                            "price": price,
                            "shares": shares,
                            "amount": cost,
                            "reason": signal.get("reason", "")
                        })
                    elif signal["signal"] == "sell" and shares > 0:
                        # 清仓卖出
                        revenue = shares * price * (1 - self.fee_rate)
                        capital += revenue
                        trades.append({
                            "date": date,
                            "action": "sell",
                            "price": price,
                            "shares": shares,
                            "amount": revenue,
                            "reason": signal.get("reason", "")
                        })
                        shares = 0

            # 记录当日权益
            equity = capital + shares * price
            equity_curve.append({"date": date, "equity": equity})

        return trades, equity_curve

    def _calculate_performance(self, equity_curve: List[Dict[str, Any]], trades: List[Dict[str, Any]]) -> Dict[str, Any]:
        """
        计算回测绩效：总收益率、年化收益率、最大回撤、胜率等
        """
        if not equity_curve or len(equity_curve) < 2:
            return {"error": "数据不足"}

        initial = equity_curve[0]["equity"]
        final = equity_curve[-1]["equity"]

        # 总收益率
        total_return = (final - initial) / initial * 100

        # 计算收益曲线的日收益率
        daily_returns = []
        for i in range(1, len(equity_curve)):
            prev = equity_curve[i - 1]["equity"]
            curr = equity_curve[i]["equity"]
            if prev > 0:
                daily_returns.append((curr - prev) / prev)

        # 最大回撤
        peak = initial
        max_drawdown = 0.0
        for point in equity_curve:
            peak = max(peak, point["equity"])
            drawdown = (peak - point["equity"]) / peak
            max_drawdown = max(max_drawdown, drawdown)

        # 胜率（从交易记录计算）
        win_count = 0
        total_rounds = 0
        if trades:
            # 简化：成对统计 buy/sell
            buy_price = None
            for t in trades:
                if t["action"] == "buy":
                    buy_price = t["price"]
                elif t["action"] == "sell" and buy_price:
                    total_rounds += 1
                    if t["price"] > buy_price:
                        win_count += 1
                    buy_price = None

        win_rate = (win_count / total_rounds * 100) if total_rounds > 0 else 0.0

        return {
            "initialCapital": initial,
            "finalEquity": final,
            "totalReturn": round(total_return, 2),
            "maxDrawdown": round(max_drawdown * 100, 2),
            "winRate": round(win_rate, 2),
            "totalTrades": total_rounds,
            "winningTrades": win_count
        }
```

---

## 4. 回测 API 路由

**文件**：`api/v1/backtest.py`

```python
from fastapi import APIRouter, HTTPException
from models.schemas.backtest import BacktestRequest, BacktestResponse
from services.backtest.strategy_runner import StrategyRunner
from typing import Dict, Any

router = APIRouter()


@router.post("/run", response_model=Dict[str, Any], summary="执行策略回测")
async def run_backtest(req: BacktestRequest):
    """
    基于 K 线数据和策略配置执行回测

    - **klines**: K 线数据
    - **strategyConfig**: 策略配置（JSON Schema 见设计文档）
    """
    try:
        runner = StrategyRunner(req.strategyConfig)
        result = runner.run_backtest(req.klines)
        return {
            "code": 200,
            "message": "success",
            "data": result
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"回测失败: {str(e)}")
```

---

## 5. Pydantic Schemas

**文件**：`models/schemas/quote.py`

```python
from pydantic import BaseModel, Field
from typing import List, Dict, Any, Optional


class KlineData(BaseModel):
    symbol: str
    date: str
    open: float
    high: float
    low: float
    close: float
    volume: Optional[int] = None
    amount: Optional[float] = None


class QuoteRequest(BaseModel):
    klines: List[KlineData] = Field(..., description="K 线数据列表")


class QuoteResponse(BaseModel):
    code: int = 200
    message: str = "success"
    data: List[Dict[str, Any]] = []
```

**文件**：`models/schemas/backtest.py`

```python
from pydantic import BaseModel, Field
from typing import List, Dict, Any


class BacktestRequest(BaseModel):
    klines: List[Dict[str, Any]] = Field(..., description="K 线数据（含技术指标）")
    strategyConfig: Dict[str, Any] = Field(..., description="策略配置（JSON）")


class BacktestResponse(BaseModel):
    code: int = 200
    message: str = "success"
    data: Dict[str, Any] = {}
```

---

## 6. 量化计算 Checklist

**新增指标计算**：
- [ ] 确认 AKQuant 是否有现成实现（优先不造轮子）
- [ ] 在 `TechIndicatorService` 中新增计算方法
- [ ] 在 `calculate_all()` 中调用新方法，将指标合并到输出
- [ ] 在 `quote.py` 中注册或更新 API 端点

**新增策略回测功能**：
- [ ] 理解 JSON 策略配置 Schema（参考 `doc/design/stock-engine/03-JSON策略配置Schema.md`）
- [ ] 在 `_match_conditions()` 中新增条件类型的判断逻辑
- [ ] 验证买入/卖出信号的正确性
- [ ] 在 `backtest.py` 中注册或更新 API 端点

**通用**：
- [ ] 使用 `core.logger.logger` 记录关键日志（info/error）
- [ ] 通过 `http://127.0.0.1:8000/docs` 测试接口
- [ ] 与 Java 服务联调验证

---

## 7. 注意事项

1. **性能**：AKQuant 虽然快，但大规模回测（数十万条 K 线）仍需时间。建议在 Service 层做缓存。
2. **数据质量**：AKShare 返回的原始数据可能有少量异常值（停牌日数据、负成交量等），在 `clean_data()` 中处理。
3. **NaN 处理**：技术指标计算的前 N 个周期会产生 NaN，返回前需处理（填充 None 或跳过）。
4. **浮点数精度**：所有返回的 float 用 `round(2)` 或 `round(4)` 避免精度问题。
5. **前复权 vs 不复权**：回测应使用前复权数据，避免除权除息导致指标跳变。
