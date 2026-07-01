# 002 因子库 — 研发主设计方案

> 参考资料索引：
> - 需求 PRD：`sdlc/prd/002-因子库/002-因子库PRD.md`
> - 因子清单参考：`sdlc/prd/002-因子库/20个标准股票因子.json`
> - 原型图：无（本模块为纯计算 / 元数据服务，不涉及独立前端页面）
>
> 项目：Stock Watcher（Java Spring Boot + SQLite + Thymeleaf + ECharts）
> 匹配过程：用户输入「002」 → 匹配到目录 `sdlc/prd/002-因子库/`

---

## 1. 需求摘要

本模块作为 Stock Watcher 量化体系的**底层计算单元**，目标是：

- 将 20+ 个技术指标（MA、EMA、MACD、KDJ、RSI、BOLL、SAR、ATR、成交量类、价格直取类）封装成**可配置、可扩展**的因子；
- 通过 HTTP API 对外暴露因子元数据（参数、输入源、输出列、预热周期），供策略管理、选股模块**动态渲染配置表单**；
- 通过 HTTP API 对外暴露**单股多因子的批量计算能力**，供策略配置器预览因子值或供指标预计算调度使用；
- 提供 `@register_factor` 装饰器形式的扩展机制，新增因子时**不修改 Java 代码**，只在 Python 端注册即可。

核心交付物：

| 项 | 位置 | 说明 |
|---|---|---|
| 因子注册表 | `stock-engine/services/factor/` | 管理所有已注册因子的元数据与计算函数 |
| 注册表 API | `stock-engine/api/v1/factors.py` / `GET /registry` | 返回完整因子清单、分类、参数定义 |
| 计算 API | `stock-engine/api/v1/factors.py` / `POST /factors` | 单股 OHLCV + 因子引用列表 → 结果字典 |
| Java 端代理 | `stock-watcher/src/main/java/com/arthur/stock/client/FactorGateway.java` | 将 Python 因子注册结果代理到 `/api/factors/registry`（供前端使用） |

---

## 2. 原型图分析（页面结构与交互流程）

**本模块无独立原型图页面**。因子库的交互全部由「策略管理」与「选股模块」复用：

| 场景 | 所在模块 | 核心动作 | 与因子库的接口契约 |
|---|---|---|---|
| 策略规则编辑器 | `003-策略管理` | 用户在可视化策略配置中选择因子 → 显示参数输入表单 → 计算预览 | 调用 `GET /api/factors/registry` 渲染参数表；调用 `POST /api/factors` 取单股多因子序列用于图表预览 |
| 选股条件编辑器 | `005-选股模块` | 用户选择一个因子（如 `MACD_DIF`）→ 设定条件阈值 → 生成 SQL WHERE 子句 | 使用 `registry` 中的 `factorKey + outputLabels + params` 做条件到数据库列名（如 `MACD_DIF`）的映射 |
| 指标预计算调度 | `003 / 005` 调度任务 | 在 daily_quote 基础上批量计算因子列写入数据库 | 使用本 PRD §9.3 的 `factorKey_paramValue` / `factorKey_outputLabel` 规则对数据库列命名，调用 `POST /api/compute/factors` 做批量计算 |

> 由于无独立 HTML 原型，本设计中「表单字段 ↔ 数据库列 ↔ JSON 字段」的映射直接以 PRD §9 的命名规则为依据。

---

## 3. 目标与非目标

| 项 | 说明 |
|---|---|
| 本次做什么（P0） | 1. 在 Python 端实现 **FactorRegistry**（装饰器注册 + 元数据检索 + 计算调用分发）；2. 按 PRD §8 实现 **20 个标准因子**（MA / EMA / BOLL / SAR / MACD / RSI / KDJ / ADX / PLUS_DI / MINUS_DI / WILLR / CCI / ATR / OBV / VOL_MA / VOL_EMA / VOLUME / CLOSE / HIGH / LOW）；3. 实现 `GET /api/compute/factors/registry`；4. 实现 `POST /api/compute/factors`；5. Java 端实现 `FactorGateway`：启动时拉取 + 1 小时 TTL 缓存 + 代理前端访问；6. 实现统一的 **结果 key 命名工具** `resultKey(factor, params, outputIndex)`，供本模块及策略/选股模块共享 |
| 本次做什么（P1） | 1. 在 Java 端新增 `/api/factors/registry` 代理端点，给前端渲染配置器；2. 新增 `/api/factors/preview`（前端传入 `ts_code` + 因子引用列表 → Java 查 OHLCV → 调 Python → 返回结果）；3. Java 端 `FactorRegistryCache`：Caffeine 缓存，可手动刷新 |
| 本次不做什么 | 1. 基本面因子（PE / PB / ROE 等）；2. 自定义 Python 代码上传的"动态因子"；3. 因子值持久化到数据库（选股模块另有独立的指标预计算表，但复用本 PRD 的列命名规则 §9.3）；4. 因子间依赖（因子输入只能是原始行情字段 `open/high/low/close/volume`）；5. 单独的因子浏览前端页面 |

---

## 4. 技术方案

### 4.1 总体架构

```
                  ┌────────────────────────────────────┐
                  │  stock-watcher (Java Spring Boot)    │
                  │                                    │
                  │  ┌─ FactorRegistryCache ──────────┐ │
                  │  │  - 启动拉取 GET /registry       │ │
                  │  │  - Caffeine TTL = 1h          │ │
                  │  │  - 手动刷新接口                 │ │
                  │  └────────┬───────────────────────┘ │
                  │           │                         │
                  │   FactorGateway.java                │
                  │   (HTTP 调用 Python side)           │
                  │           │                         │
                  │  /api/factors/registry              │
                  │  /api/factors/preview               │
                  └───────────┬─────────────────────────┘
                              │ HTTP JSON :8000
                              ▼
           ┌────────────────────────────────────────────────────┐
           │   stock-engine (Python FastAPI + akquant)           │
           │                                                      │
           │  api/v1/factors.py                                   │
           │    GET  /api/compute/factors/registry → registry     │
           │    POST /api/compute/factors         → results dict │
           │                                                      │
           │  services/factor/                                    │
           │    factor_registry.py (全局单例 + 装饰器注册)        │
           │    factors_*.py      (20 个内置因子)                │
           │    factor_utils.py   (resultKey / lookback 估算)    │
           │                                                      │
           │  models/schemas/factor.py (Pydantic 请求/响应模型)   │
           └────────────────────────────────────────────────────┘

          输入数据流向: Java(SQLite daily_quote) → HTTP JSON →
                          Python akquant → 返回 JSON 结果

          元数据流向: Python(FactorRegistry.dump()) → HTTP JSON →
                          Java FactorRegistryCache → 前端 Thymeleaf JS
```

本模块落在**现有 Python 计算服务 stock-engine 的扩展层**，与已存在的 `api/v1/quote.py`（指标计算）、`services/indicator/tech_indicator.py` 同构。Java 侧通过 `client/` 包下的新 `FactorGateway` 调用，沿用 `TushareClient` 那种 HTTP JSON 调用模式。

### 4.2 模块划分

| 模块 | 职责 | 路径 / 类名 | 对应原型图页面 |
|---|---|---|---|
| factor_registry (Python) | 注册 + 检索因子元数据，分发计算调用 | `stock-engine/services/factor/factor_registry.py`，类名 `FactorRegistry` | 无 |
| factors_* (Python) | 20 个内置因子计算函数，全部走 `akquant.talib` | `stock-engine/services/factor/factors_builtin.py`（按分类组织内部结构，函数名以 `compute_` 开头） | 无 |
| factor_api (Python) | FastAPI 路由：`GET /registry`、`POST /factors`、`GET /health` | `stock-engine/api/v1/factors.py` | 策略配置器引用 |
| factor_utils (Python) | 结果 key 命名工具、lookback 估算、参数校验 | `stock-engine/services/factor/factor_utils.py` | 无 |
| FactorGateway (Java) | 调用 Python 服务，获取 registry / 计算因子 | `com.arthur.stock.client.FactorGateway.java` | 无 |
| FactorController (Java) | 前端代理端点：`/api/factors/registry`、`/api/factors/preview` | `com.arthur.stock.controller.FactorController.java` | 策略配置器中的因子参数表 |

### 4.3 关键流程

**流程一：Java 启动时拉取因子注册表元数据（冷启动缓存）**

```
1. StockWatcherApplication 启动
2. 触发 CommandLineRunner(FactorRegistryLoader)
3.  → FactorGateway.fetchRegistry()
     → HTTP GET http://127.0.0.1:8000/api/compute/factors/registry
4. Python FactorRegistry.dump()
     → 遍历所有已注册 factor
     → 组装 { factorKey, displayName, category, description,
             params[], inputs[], multiOutput, outputLabels[],
             defaultOutputIndex, lookbackHint, lookbackDefault }
5. 返回 JSON → Java 写入 Caffeine cache（key = "registry", ttl = 1h）
6. 启动完成；后续前端通过 Java /api/factors/registry 直接拿缓存
```

> 若 Python 服务不可用（未启动），FactorGateway 返回"空的 fallback 注册表"并记录 WARN 日志，不阻塞主应用启动；Python 服务恢复后，下一次读取将触发缓存刷新。

**流程二：前端配置器渲染因子参数表单**

```
前端策略配置器初始化
 → GET /api/factors/registry (Java 代理)
   → FactorGateway.getRegistry() → Caffeine 命中或远程调用
 → 按 category 分组显示
 → 用户选中因子（如 MACD）
   → 解析 params 字段（fastperiod / slowperiod / signalperiod）
   → 动态生成 input / select 表单
 → 填充参数 → 提交到策略保存
```

**流程三：单股多因子批量计算（策略预览 / 指标预览）**

```
前端请求: POST /api/factors/preview
 body: { tsCode: "000001.SZ",
         factors: [{factor:"MA", params:{timeperiod:5}},
                   {factor:"MACD", params:{fastperiod:12,slowperiod:26,signalperiod:9}}] }

1. Java FactorController 校验登录 + tsCode 合法性
2. Java DailyQuoteService.fetchDailyQuote(tsCode) 读取最近 N 条 OHLCV
3. Java FactorGateway.computeFactors(ohlcv, factors)
     → HTTP POST /api/compute/factors (Python)
       body: { taskId, ohlcv:[{date,open,high,low,close,volume}], factors:[...] }
4. Python:
     a) FactorRegistry.validate(factors)  逐个查 factorKey
     b) 按 inputs 抽取所需行情列（如 high/low/close）
     c) 逐个调用 compute_xxx(ndarray, **params)
     d) resultKey(factor, params, outputIndex) 命名每个输出
     e) 组装 { dates, results: { key: [v0, v1, ...] } }
5. 返回 Java → 前端 ECharts 渲染
```

**流程四：Python 端扩展新因子**（开发者流程）

```python
@register_factor(
    factor_key="ATR",
    display_name="真实波幅均值",
    category="VOLATILITY",
    description="基于 high/low/close 计算 N 日平均真实波幅",
    params=[{"name":"timeperiod","displayName":"周期","type":"INT","defaultValue":14,"min":1,"max":500}],
    inputs=["high","low","close"],
    multi_output=False,
    output_labels=[],
    lookback_hint="timeperiod - 1",
    lookback_default=13,
)
def compute_atr(high, low, close, *, timeperiod=14):
    return akquant.talib.ATR(high, low, close, timeperiod=timeperiod)
```

**缓存策略**：

| 缓存项 | 位置 | Key | TTL | 失效方式 |
|---|---|---|---|---|
| 因子注册表 | Java Caffeine | `"registry"` | 1h | 提供 `POST /api/factors/registry/refresh` 强制刷新；Python 侧不主动通知 |
| 单股因子计算结果 | Java（不缓存） | — | — | 计算结果实时，因为同一因子同一参数对不同股票不同时间窗口结果不同 |

### 4.4 类设计 / 接口契约

#### Python 侧（核心）

**Pydantic Schema — `models/schemas/factor.py`**

```python
from pydantic import BaseModel, Field, field_validator
from typing import List, Dict, Any, Optional, Literal

FactorCategory = Literal["TREND","MOMENTUM","VOLATILITY","VOLUME","PRICE"]


class FactorParamDef(BaseModel):
    name: str
    displayName: str
    type: Literal["INT","FLOAT","ENUM"] = "INT"
    defaultValue: Optional[float] = None
    min: Optional[float] = None
    max: Optional[float] = None
    step: Optional[float] = None
    enumValues: Optional[List[Dict[str, Any]]] = None


class FactorMeta(BaseModel):
    factorKey: str
    displayName: str
    category: FactorCategory
    description: str = ""
    params: List[FactorParamDef] = []
    inputs: List[str] = Field(..., description="从 open/high/low/close/volume 中选取")
    multiOutput: bool = False
    outputLabels: List[str] = []
    defaultOutputIndex: int = 0
    lookbackHint: Optional[str] = None
    lookbackDefault: Optional[int] = None

    @field_validator("inputs")
    @classmethod
    def _inputs_in_whitelist(cls, v):
        allowed = {"open","high","low","close","volume"}
        if not v or not set(v).issubset(allowed):
            raise ValueError(f"inputs 必须是 {allowed} 的子集")
        return v


class FactorRegistryResponse(BaseModel):
    factors: List[FactorMeta]
    count: int
    categories: List[str]


# -------- 计算请求 / 响应 --------

class FactorReference(BaseModel):
    factor: str = Field(..., description="注册表中的 factorKey，如 MA / MACD")
    params: Optional[Dict[str, Any]] = Field(default_factory=dict)
    outputIndex: Optional[int] = Field(
        default=None,
        description="仅用于调用方标识自己关心的输出列；"
                    "multiOutput=false 时可省略。"
                    "服务端始终返回所有输出列。"
    )


class OhlcvItem(BaseModel):
    date: str = Field(..., description="YYYYMMDD 或 ISO 字符串，与 Java 端保持一致")
    open: float
    high: float
    low: float
    close: float
    volume: Optional[float] = None


class FactorComputeRequest(BaseModel):
    taskId: Optional[str] = None
    stockCode: Optional[str] = None
    ohlcv: List[OhlcvItem]
    factors: List[FactorReference]


class FactorComputeResponse(BaseModel):
    taskId: Optional[str] = None
    computeMs: Optional[float] = None
    dates: List[str]
    results: Dict[str, List[Optional[float]]]
```

**统一外层包装**（沿用 `code/message/data`）由 `main.py` 统一处理，新路由与 `quote.py` 保持一致。

#### Java 侧（代理 / 缓存）

**FactorGateway.java**

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class FactorGateway {
    @Value("${python.compute.url:http://127.0.0.1:8000}")
    private String pythonUrl;

    private final RestTemplate restTemplate;  // 复用 WebConfig 中已配置的 RestTemplate
    private final Cache<String, Object> factorCache;  // Caffeine, 见 CacheConfig 扩展

    /** 拉取注册表，含缓存 */
    @SuppressWarnings("unchecked")
    public FactorRegistryVO getRegistry() {
        return (FactorRegistryVO) factorCache.get("registry", k -> fetchRegistry());
    }

    /** 强制刷新缓存（管理员接口触发） */
    public void refreshRegistry() {
        FactorRegistryVO fresh = fetchRegistry();
        factorCache.put("registry", fresh);
    }

    private FactorRegistryVO fetchRegistry() {
        // 调用 GET http://127.0.0.1:8000/api/compute/factors/registry
        // 失败时返回空 fallback，并 log.warn
    }

    /** 单股多因子计算 */
    public FactorComputeResultVO computeFactors(List<DailyQuoteDO> ohlcv,
                                                List<FactorReference> factors) {
        // 组装 JSON → POST /api/compute/factors
    }
}
```

**FactorController.java**

```java
@RestController
@RequestMapping("/api/factors")
@RequiredArgsConstructor
public class FactorController {
    private final FactorGateway factorGateway;
    private final DailyQuoteService dailyQuoteService;

    @GetMapping("/registry")
    public ApiResponse<FactorRegistryVO> registry() {
        return ApiResponse.success(factorGateway.getRegistry());
    }

    @PostMapping("/preview")
    public ApiResponse<FactorComputeResultVO> preview(
            @RequestBody FactorPreviewRequest body) {
        // 登录态由 AuthInterceptor 保证
        // 1. 校验 tsCode
        // 2. dailyQuoteService 读取最近 N 条
        // 3. factorGateway.computeFactors(ohlcv, factors)
        // 4. 返回结果，前端 ECharts 渲染
    }

    @RequireAdmin
    @PostMapping("/registry/refresh")
    public ApiResponse<String> refresh() {
        factorGateway.refreshRegistry();
        return ApiResponse.success("refreshed", null);
    }
}
```

### 4.5 与现有系统的集成点

| 能力 | 位置 | 说明 |
|---|---|---|
| **定时任务** | 不新增（因子预计算由选股模块调度完成） | Java `DailyUpdateTask` 是现有定时任务；因子计算以按需调用模式为主，不定时常驻 |
| **缓存** | Java `CacheConfig` 扩展 Caffeine | 新增 `factorRegistryCache`（maximumSize=1，expireAfterWrite=1h） |
| **枚举** | Python `FactorCategory` 字面量 | `TREND / MOMENTUM / VOLATILITY / VOLUME / PRICE`，与 PRD §8 一致 |
| **新表** | 本模块不新增表 | 因子值持久化与指标预计算由 005-选股模块 承担，列名规则复用本 PRD §9.3 |
| **接口** | Java `FactorController`（新），Python `api/v1/factors.py`（新） | 见 §4.4 / HTTP API 设计文档 |
| **权限** | `/api/factors/*` 默认需要登录；`/registry/refresh` 需 `@RequireAdmin` | 复用现有 `AuthInterceptor` 与 `AdminCheckAspect` |
| **Tushare 调用** | 不直接 | OHLCV 数据来自 Java 的 SQLite daily_quote 表，Python 不直连 Tushare |

### 4.6 异常与错误码

| 场景 | HTTP | Java ErrorCode 映射 | Python 响应 code | 消息 |
|---|---|---|---|---|
| 请求参数缺失 / 不合法 | 400 | BAD_REQUEST | 400 | "请求参数错误：字段名 + 原因" |
| 未登录访问 `/api/factors/*` | 401 | UNAUTHORIZED | — | 由 AuthInterceptor 返回 |
| 非管理员刷新 registry | 403 | FORBIDDEN | — | 由 AdminCheckAspect 返回 |
| 请求的 factorKey 不在注册表中 | 404 | NOT_FOUND | 404 | "未找到因子：{factorKey}" |
| `inputs` 缺少某列（如 ATR 缺失 `high/low/close`） | 400 | BAD_REQUEST | 400 | "因子 {factorKey} 需要 inputs: high/low/close" |
| 调用 Python 超时 / 连接失败 | 502 / 503 | 500（临时，暂不新增错误码） | — | "Python 计算服务不可用" |
| Python 侧计算异常（akquant 抛错 / NaN 输入） | — | — | 400 / 500 | "因子计算失败：{reason}" |
| 重复注册同一 factorKey | —（启动期 warn + 覆盖） | — | — | "factorKey={x} 已存在，将覆盖前一次注册"（仅日志） |

> **说明：** 本 PRD 不新增 Java `ErrorCode` 枚举值；所有业务异常全部复用已有 `BAD_REQUEST / UNAUTHORIZED / FORBIDDEN / NOT_FOUND / CONFLICT(500)`。

### 4.7 复用清单（基于实时扫描结果）

#### 表级

| 现有表 | 复用决策 | 说明 |
|---|---|---|
| `sys_user` | 直接复用（鉴权 / 角色判断） | `/api/factors/registry/refresh` 用 role=ADMIN 判断 |
| `daily_quote` | **直接复用** | Java 侧读取 OHLCV 作为因子计算的输入数据源 |
| `stock_basic` | 直接复用（tsCode 校验 + 名称展示） | 预览接口用 `ts_code` 查股票信息，校验是否存在 |
| `trade_cal` | 直接复用（交易日对齐） | 读取最近 N 条 K 线时可按交易日过滤，OHLCV 已天然有序 |
| `adj_factor` | 间接复用（KlineService 返回复权价） | 因子计算输入应使用前复权数据，沿用 `KlineService.getKlineData` |
| `dividend` | 不使用 | 与因子计算无直接关系 |
| `sys_watchlist` | 不使用 | 本模块不处理自选股 |
| **新表** | **不新增** | 因子值持久化由 005-选股模块承担；本模块仅计算、不存库 |

#### Java Controller 级

| 现有 Controller | 路径前缀 | 复用决策 | 说明 |
|---|---|---|---|
| `KlineController` | `/api/kline` | **直接复用**（作为参考模式） | 命名风格、`ApiResponse<T>` 返回形式为 FactorController 参考 |
| `StockBasicController` | `/api/stocks` | 直接复用 | 前端预览因子时需校验 tsCode 是否有效 |
| `AuthController` | `/api/auth` | 直接复用 | 登录态与 `@RequireAdmin` 沿用 |
| `PageController` | `/` | 直接复用（不改动） | 仅 HTML 页面，本设计涉及的前端 JS 通过 Thymeleaf fragment 注入 |
| **FactorController（新）** | `/api/factors` | **新增** | 无冲突；与现有 `kline/stocks/auth` 前缀完全不同 |

#### Python 服务级

| 现有模块 | 复用决策 | 说明 |
|---|---|---|
| `api/v1/quote.py` 模式 | **直接复用**（命名与结构一致） | 新路由 `api/v1/factors.py` 遵循同样的 Pydantic + Router + ApiResponse 包裹风格 |
| `services/indicator/tech_indicator.py` | 参考实现 | akquant 调用模式一致，但 `FactorRegistry` 为新单例，不直接依赖 `TechIndicatorService` |
| `core/exceptions.py` | 直接复用 | `ParameterValidationException` / `CalculationException` 足以覆盖本模块错误路径 |
| `config.py` / `main.py` | 直接复用 | 新增 router 需要在 `api/v1/__init__.py` 中 `include_router` |

#### 错误码级

| 现有 ErrorCode | 复用决策 | 说明 |
|---|---|---|
| `BAD_REQUEST(400)` | 直接复用 | 参数缺失 / factorKey 不合法 / inputs 不足 |
| `UNAUTHORIZED(401)` | 直接复用 | 未登录访问 `/api/factors/*` |
| `FORBIDDEN(403)` | 直接复用 | 非管理员调用 refresh |
| `NOT_FOUND(404)` | 直接复用 | 因子不存在 / 股票不存在 |
| `CONFLICT(409)` | 不使用 | 本模块无写库操作 |
| **新增** | 暂不新增 | 所有异常场景均可被以上 4 个覆盖 |

#### DTO / VO 级

| 现有类 | 复用决策 |
|---|---|
| `ApiResponse<T>` | **直接复用** | Java FactorController 返回 `ApiResponse<FactorRegistryVO>` |
| `KlineDataVO` | 参考实现 | 输出结构类似 `[{date, open, high, ...}]`，但本模块直接返回 `results: {key: [...]}`，便于前端对齐 X 轴日期 |
| `DailyQuoteDO` | **直接复用** | Java 侧从 DB 读出的原始 OHLCV 作为 FactorGateway 入参 |

---

## 5. 验收要点

- [ ] P0 功能：Python 服务启动后，`GET /api/compute/factors/registry` 返回 **20 个已定义因子**，字段结构与 §4.4 Pydantic `FactorMeta` 一致
- [ ] P0 功能：`POST /api/compute/factors` 能处理包含 OHLCV 的请求，对每个请求因子返回一条等长数组；预热不足位置为 `null`
- [ ] P0 功能：结果 key 命名符合 §9.3 规则（单输出：`factorKey_paramValue`；多输出：`factorKey_outputLabel`；价格直取类：`close / high / low / volume`），可用自动化测试断言
- [ ] P0 功能：对 MA/EMA/MACD/KDJ/RSI/BOLL/SAR/ATR 等关键因子用 Python akquant 做手工对照测试，结果与 akquant 直接调用一致
- [ ] P1 功能：Java `FactorController#registry` 可正常代理，启动拉取 + 1 小时缓存生效
- [ ] P1 功能：Java `FactorController#preview`（需登录）返回前端可直接绘图的 JSON 结构，与请求中的因子引用一一对应
- [ ] 权限：未登录访问 `/api/factors/preview` 返回 401；非管理员访问 `/api/factors/registry/refresh` 返回 403
- [ ] 异常收敛：Python 侧传入非法 `factorKey` 或缺失必要 `inputs` 时，返回 `code != 0` 的 JSON 响应而非抛异常
- [ ] 命名一致性：所有 JSON 字段为小驼峰（`factorKey / displayName / outputLabels / defaultOutputIndex / lookbackHint / lookbackDefault`）
- [ ] 扩展性：按 §4.3 流程四新增一个示例因子（如 `TRIX`，不在本次交付范围但应可直接扩展），能自动出现在 registry 响应中

---

## 6. 风险与 TODO

| 项 | 说明 |
|---|---|
| **Python ↔ Java 启动顺序** | Java 启动时调用 Python 拉取 registry，若 Python 尚未启动，则走 fallback 空注册表。建议在 `application.yml` 增加 `python.compute.url` 配置，并在启动日志中明确打印"因子注册表拉取结果: 成功/失败" |
| **因子注册表缓存过期** | 1 小时 TTL 意味着 Python 端新增因子后最长 1 小时才能在 Java 前端生效；提供 `/registry/refresh` 管理员接口作为应急 |
| **akquant 接口变动** | PRD 依赖 `akquant.talib.MA / EMA / BBANDS / SAR / MACD / RSI / STOCH / ADX / PLUS_DI / MINUS_DI / WILLR / CCI / ATR / OBV` 等函数；akquant 版本升级可能影响函数签名。在 `compute_*` 实现层做薄封装，集中控制对 akquant 的依赖 |
| **结果 key 命名规则跨模块一致性** | 本 PRD 的 §9.3 规则同时服务策略配置、选股预计算表列、前端图表 key。需把 `resultKey()` 实现集中到 `factor_utils.py`，并在 Java 侧提供一个纯静态方法 `FactorKeyUtil.resultKey(factor, params, outputIndex)`，与 Python 逻辑等价 |
| **原型图缺失** | 本需求无独立 HTML 原型，因子相关 UI 由 003-策略管理 和 005-选股模块 负责；设计阶段已在各接口中标注"对应策略配置器中因子参数表"，待策略模块产出后再做一次端到端对齐 |
| **多输出因子的 output_index 语义** | PRD 规定"服务端始终返回全部输出列，`outputIndex` 仅用于调用方标识自己关心的列"。该语义在 Java 代理层需要保持：`FactorGateway#computeFactors` 原样转发 outputIndex；前端解析 `results` 时以 `MACD_DIF` 等 key 直接访问，不需要再做"只取第 N 条"的过滤。需在 API 文档中显式提示 |
| **SQLite 与因子预计算** | 本设计不新增表；若后续选股模块要新增指标列，其列名规则已由本 PRD §9.3 明确，无需再做额外协商 |

---

## 7. 自检记录（设计时自检项）

- [x] 所有表/字段为 snake_case（本次不新增表，检查点通过）
- [x] 所有 JSON 请求/响应字段为 camelCase（`factorKey / displayName / outputLabels / defaultOutputIndex / lookbackHint / lookbackDefault / outputIndex` 等）
- [x] SQLite 数据类型正确（本次不新增表，但已有 `daily_quote` 的 `REAL/TEXT` 仍作为输入使用，检查点通过）
- [x] 未设计与现有 Controller 路径语义重复的新接口（`/api/factors/...` 与现有 `/api/kline / /api/stocks / ...` 无冲突）
- [x] 复用 `ErrorCode.java` 已有常量，未新增同义 code
- [x] `PageController` 未被当作 REST API 参与复用判断
- [x] 01-main-design.md 头部包含「参考资料索引」，引用了 PRD 及因子清单文件路径
- [x] 因子元数据与 PRD §8.1 清单一一对应（MA、EMA、BOLL、SAR、MACD、RSI、KDJ、ADX、PLUS_DI、MINUS_DI、WILLR、CCI、ATR、OBV、VOL_MA、VOL_EMA、VOLUME、CLOSE、HIGH、LOW），共 20 个
- [x] 结果 key 命名规则与策略模块/选股模块共享 §9.3 规范
- [ ] **待实施阶段完成项**：以上 5. 验收要点中的所有功能项，将在代码实现完成后勾选
