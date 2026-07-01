# 003 策略管理 — 研发主设计方案

> 参考资料索引：
> - 需求 PRD：`sdlc/prd/003-策略管理/003-策略管理PRD.md`
> - Schema 参考：`sdlc/prd/003-策略管理/策略配置Schema.md`
> - 原型图：
>   - `sdlc/prd/003-策略管理/prototype/index.html` — 模块入口导航卡片
>   - `sdlc/prd/003-策略管理/prototype/strategy-list.html` — 策略列表页
>   - `sdlc/prd/003-策略管理/prototype/strategy-editor.html` — 策略编辑器（左右双栏 + Tab）
>   - `sdlc/prd/003-策略管理/prototype/strategy-versions.html` — 版本历史与对比
>   - `sdlc/prd/003-策略管理/prototype/universe-config.html` — 选股范围与前置过滤配置
>   - `sdlc/prd/003-策略管理/prototype/rule-tree-debug.html` — 规则树调试面板（开发辅助）
>
> 项目：Stock Watcher（Java Spring Boot + SQLite + Thymeleaf + ECharts）
> 匹配过程：用户输入「003」 → 匹配到目录 `sdlc/prd/003-策略管理/`

---

## 1. 需求摘要

本模块是 Stock Watcher 量化体系的**策略定义层**，向上承接因子库的计算能力，向下为回测中心与选股模块提供结构化输入。核心目标是：

- 用**可视化规则配置**替代手写 Python 策略代码，用户通过前端页面即可构建买入/卖出规则树（AND/OR 嵌套 + 比较节点 + 因子引用/静态值/算术表达式三态表达式节点）；
- 策略以 JSON 形式持久化到 `quant_strategy` 主表，每一次用户"保存为版本"时在 `quant_strategy_version` 表生成一条快照，支持版本对比与回滚；
- 与因子库保持**Schema 对齐**：策略 JSON 中出现的 `factorKey` / `params` / `outputIndex` 与 `GET /api/factors/registry` 结果一致；因子结果列命名与 `002-因子库 PRD §9.3` 的 `factorKey_paramValue` / `factorKey_outputLabel` 规则一致；
- 选股草稿场景通过 `quant_strategy(status=DRAFT, category=SCREEN_DRAFT)` 共用一套表结构，避免重复建表；
- 止损止盈与最大持仓天数为**特殊卖出规则节点**（非通用 compare 节点），在前端以独立区块编辑，在 Python 端作为独立交易逻辑执行。

| 模块 | Java 端职责 | Python 端职责 | 复用来源 |
|------|-----------|--------------|---------|
| 持久化 | 策略 CRUD、版本快照、状态流转（DRAFT ↔ ACTIVE ↔ ARCHIVED）、与回测结果关联 | — | 本模块新建表 |
| 前端配置器 | 策略列表/编辑器/版本对比/选股范围页面（Thymeleaf） | — | 原型图 4 个 HTML |
| 规则树解析 | 仅做基础 JSON Schema 校验（必填字段、参数类型） | RuleParser：提取去重因子规格列表、参数路径列表、条件评估器 | 新建 |
| 预览/调试 | 单条 K 线信号模拟（调用 Python 规则解析） | 执行规则树计算、返回命中明细 | 复用 `002-因子库` `POST /api/factors` |

---

## 2. 原型图分析（页面结构与交互流程）

| 页面（原型图） | 核心功能 | 关键表单字段 | 主要操作 | 与其他页面的关联 |
|---------------|---------|-------------|---------|-----------------|
| `prototype/index.html` | 模块导航入口，展示策略数、已激活数、草稿数、快捷跳转卡片 | 无表单，纯展示 + 链接 | 「新建策略」→ strategy-editor；「策略列表」→ strategy-list；「调试」→ rule-tree-debug | 跳转到其他 5 个页面 |
| `prototype/strategy-list.html` | 策略列表页，卡片展示 + 筛选（按状态 / 分类） | 搜索框（策略名/描述关键字）；筛选 chip（状态：DRAFT/ACTIVE/ARCHIVED、分类：TREND/MEAN_REVERT/MOMENTUM/SCREEN_DRAFT） | 「新建策略」→ editor；卡片「编辑」→ editor；「立即回测」→ 调用回测接口（后续 004 模块）；「版本」→ versions；「归档」/「恢复」 | 进入 editor / versions / 触发回测 |
| `prototype/strategy-editor.html` | **核心页面**：左侧 Tab（基本信息 / 买入规则 / 卖出规则 / 选股范围 / 仓位管理），右侧预览（自然语言 / JSON / 参数清单）；底部操作栏 | 策略名称、策略代码、分类、描述；买入规则树（AND/OR 嵌套 + compare 条件）；卖出规则（条件卖出 OR 树 + 止损百分比/止盈百分比/最大持仓天数）；选股范围（ALL/INDEX_300/INDEX_500/WATCHLIST/CUSTOM_CODES 及自定义代码列表）；前置过滤（剔除 ST、次新股天数、最小日均成交额）；仓位管理（单票最大仓位、最大持仓数、最小持有天数） | 「保存草稿」（仅更新 DRAFT 状态主表）；「保存为版本 N+1」（写入 version 表）；「立即回测」→ 回测中心；「查看历史版本」→ versions | 跳转到 versions / universe-config 子配置 |
| `prototype/strategy-versions.html` | 策略版本时间线 + 双版本选择器 + JSON diff | 版本 1 选择框、版本 2 选择框 | 「切换版本对比」（重新 diff）；「回滚为当前版本」（将所选版本规则回写主表并新建版本）；「关联回测报告」链接（后续 004 模块） | 跳回 editor；链接到 004 回测报告页 |
| `prototype/universe-config.html` | 选股范围与前置过滤详细配置 | 范围选择；自定义股票代码文本框（多行/逗号分隔）；三个开关（排除 ST、次新股阈值、日均成交额阈值） | 「保存」→ 写入 quant_strategy 的 universe/pre_filters JSON；「预览符合条件的股票数量」→ Java 端查 stock_basic | 返回 editor 对应 Tab |
| `prototype/rule-tree-debug.html` | JSON 编辑器 + 语法校验 + 因子引用统计 + 参数路径提取 + 单条 K 线信号模拟 | JSON 文本框；可选的 ts_code / trade_date | 「校验」→ 返回错误；「提取因子」→ 返回去重 FactorSpec 列表；「提取参数」→ 返回所有可调参数及路径；「模拟信号」→ 返回 buy_rules / sell_rules 的命中情况；「导出 JSON」→ 复制 | 从 editor 的「调试」按钮打开 |

---

## 3. 目标与非目标

| 项 | 说明 |
|---|---|
| **本次做什么（P0）** | 1. 新建 `quant_strategy` 主表 + `quant_strategy_version` 版本快照表；2. Java 端实现策略 CRUD + 版本快照 + 状态流转（DRAFT→ACTIVE→ARCHIVED）；3. 实现策略规则 JSON 的基础 Schema 校验（operator/compare/type 等关键字段结构合法 + 因子 key 在 registry 中存在）；4. 策略列表 / 编辑器 / 版本对比 / 选股范围 / 调试面板 5 个前端页面（Thymeleaf + Bootstrap 5）；5. `RuleParser`（Python 端）：提取去重因子规格集合、参数路径集合、单 bar 信号评估函数；6. Java ↔ Python 接口：规则解析 / 信号模拟 / 版本 JSON diff |
| **本次做什么（P1）** | 1. 策略列表的筛选与搜索；2. 「从选股草稿升级为完整策略」一键操作：填充默认卖出规则、空仓位管理；3. 策略被回测模块引用时的锁定标记（不允许删除）；4. 自然语言描述生成（JSON → 中文自然语言，前端根据关键字段动态渲染，无需 NLP）；5. 选股草稿保存时 category=SCREEN_DRAFT |
| **本次不做什么** | 1. 因子值预计算（独立模块或在选股/回测模块中实现，此处只存规则）；2. 回测引擎本身（由 004 回测中心实现，本模块只提供 strategy JSON 作为输入）；3. 信号中心推送（004 后续）；4. 多周期策略（日线 + 60 分钟混合）；5. 多用户隔离与权限精细控制（暂以 `@RequireAdmin` 统一限制，单用户场景够用）；6. 手动细粒度 diff（按行保存策略变更历史），版本用整份 JSON 快照即可 |

---

## 4. 技术方案

### 4.1 总体架构

```
                    ┌─────────────────────────────────────────────────────────┐
                    │   stock-watcher (Java Spring Boot, SQLite)              │
                    │                                                         │
                    │  ┌─ StrategyController ──────────────────────────────┐ │
                    │  │  GET  /api/strategies            (列表 + 筛选)     │ │
                    │  │  GET  /api/strategies/{id}     (详情 + JSON)        │ │
                    │  │  POST /api/strategies           (新建策略)          │ │
                    │  │  PUT  /api/strategies/{id}     (更新主表/保存草稿) │ │
                    │  │  POST /api/strategies/{id}/versions (保存版本)     │ │
                    │  │  GET  /api/strategies/{id}/versions (时间线)        │ │
                    │  │  GET  /api/strategies/{id}/versions/{v}  (快照)    │ │
                    │  │  POST /api/strategies/{id}/versions/{v}/rollback   │ │
                    │  │  POST /api/strategies/{id}/status      (DRAFT/    │ │
                    │  │                                                ACTIVE/ARCHIVED) │
                    │  │  POST /api/strategies/parse            (规则解析,  │ │
                    │  │                                     调 Python)     │ │
                    │  │  POST /api/strategies/debug/simulate (信号模拟,  │ │
                    │  │                                     调 Python)     │ │
                    │  │  POST /api/strategies/debug/diff     (JSON 对比)  │ │
                    │  └────────────────────────────┬────────────────────────┘ │
                    │                               │                            │
                    │       ┌─ StrategyService ────────────────────────────┐ │
                    │       │  createStrategy / updateStrategy             │ │
                    │       │  saveVersion / listVersions / getVersion     │ │
                    │       │  rollbackVersion / updateStatus              │ │
                    │       │  validateRuleTreeJSON (factorKey ∈ registry) │ │
                    │       │  estimateUniverseCount(选股范围预览)          │ │
                    │       └─────────────────────┬───────────────────────────┘ │
                    │                             │                               │
                    │   ┌─ quant_strategy (SQLite) ─┐  ┌─ quant_strategy_version┐ │
                    │   │  id, strategy_key, name, │  │  id, strategy_id,  │ │
                    │   │  category, status, buy,  │  │  version, buy_rules,│ │
                    │   │  sell_rules, position_   │  │  sell_rules, position│ │
                    │   │  sizing, universe, pre_  │  │  sizing, universe,   │ │
                    │   │  filters, created_at,    │  │  pre_filters, change_│ │
                    │   │  updated_at              │  │  note, backtest_id,   │ │
                    │   │                          │  │  created_at           │ │
                    │   └─────────────────────────┘  └─────────────────────────┘ │
                    │                               │                              │
                    │     FactorRegistryCache（复用 002 因子库）                  │
                    │     → GET /api/factors/registry（校验 factorKey 合法性）     │
                    │     → POST /api/factors/preview（单股多因子计算, 调试面板用）│
                    └──────────────────────────────┬──────────────────────────────┘
                                                   │ HTTP JSON 127.0.0.1:8000
                                                   ▼
             ┌─────────────────────────────────────────────────────────────────────┐
             │          stock-engine (Python FastAPI + akquant)                     │
             │                                                                     │
             │  api/v1/strategy/                                                     │
             │    POST /api/compute/strategy/parse          → factor_specs +       │
             │                                                  param_paths + AST │
             │    POST /api/compute/strategy/simulate       → buy/sell 命中信号   │
             │    POST /api/compute/strategy/diff             → JSON diff (可在   │
             │                                                  Java 侧做, 备用)   │
             │                                                                     │
             │  services/strategy/                                                     │
             │    rule_parser.py   (JSON → AST, 节点类型: and/or/compare/expr    │
             │    factor_spec.py   (factor_key + params -> 不可变, 可去重)        │
             │    param_path.py    (JSON path + 默认值, 参数优化用)                │
             │    sell_rules.py    (stop_loss / take_profit / max_holding_days   │
             │                     特殊节点, 不参与通用 compare 树)                │
             │  models/schemas/strategy.py (Pydantic 规则树模型)                  │
             └─────────────────────────────────────────────────────────────────────┘

  数据流向: 前端表单 → Java StrategyController/Service → JSON 序列化 →
            quant_strategy / quant_strategy_version(SQLite)
  验证流向: 前端 JSON → Java validateRuleTreeJSON → 查 factorKey 是否在
            FactorRegistryCache 中 → 通过 / 报 400 BAD_REQUEST
  调试流向: rule-tree-debug.html → /api/strategies/parse → Java 转发给
            Python RuleParser → 返回 factor_specs / param_paths
```

### 4.2 模块划分

| 模块 | 职责 | Java 包路径 / Python 模块路径 | 主要类/文件 | 对应原型图页面 |
|------|------|-------------------------------|------------|--------------|
| 策略列表 | 策略卡片列表、搜索与筛选、快捷动作 | `controller/StrategyController.java` + `templates/pages/strategy/list.html` | `StrategyController.list()`，`StrategyService.queryList(keyword, status, category)` | strategy-list.html |
| 策略编辑器（核心） | 左右双栏：Tab 编辑 + 预览；保存草稿/保存版本/立即回测 | `templates/pages/strategy/editor.html` + `controller/StrategyController.java` 的 create/get/update | `StrategyService.create()`, `update()`, `saveVersion()`，前端 JS 渲染规则树表单 | strategy-editor.html |
| 版本管理 | 版本时间线、版本快照、版本对比、回滚 | `service/impl/StrategyServiceImpl.java` + `templates/pages/strategy/versions.html` | `listVersions(id)`, `getVersion(id, v)`, `rollbackVersion(id, v)` | strategy-versions.html |
| 选股范围配置 | 范围选择 + 自定义代码 + 预览符合条件的股票数量 | `controller/StrategyController.java`（`estimateUniverse()` 子接口） | `StrategyService.estimateUniverseCount(universeValue, customCodes, preFilters)`，查询 stock_basic | universe-config.html |
| 规则树调试 | JSON 编辑 + 校验 + 因子提取 + 参数提取 + 信号模拟 | `templates/pages/strategy/rule-tree-debug.html` + `controller/StrategyDebugController.java` | `parse()`, `simulate()`, `diff()` 三个 Java 端点 + Python 对应接口 | rule-tree-debug.html |
| Python 规则解析 | RuleParser / FactorSpec / ParamPath；特殊卖出规则 | `stock-engine/services/strategy/*.py` | `RuleParser.parse(buy_rules)` → `ParseResult{ factorSpecs, paramPaths, conditionEvaluator }` | 被 Java 调试面板调用 |

**Java 端目录约定（与现有 `com.arthur.stock.*` 一致）：**

```
src/main/java/com/arthur/stock/
├── controller/
│   ├── StrategyController.java         // /api/strategies  REST
│   └── StrategyDebugController.java    // /api/strategies/debug/*
├── service/
│   ├── StrategyService.java           // 接口
│   └── impl/StrategyServiceImpl.java  // 实现
├── mapper/
│   ├── QuantStrategyMapper.java
│   └── QuantStrategyVersionMapper.java
├── model/
│   ├── QuantStrategyDO.java
│   └── QuantStrategyVersionDO.java
└── dto/strategy/
    ├── StrategyCreateRequest.java
    ├── StrategyUpdateRequest.java
    ├── StrategyListResponse.java
    ├── StrategyDetailResponse.java
    ├── StrategyVersionResponse.java
    ├── ParseRequest.java / ParseResponse.java
    ├── SimulateRequest.java / SimulateResponse.java
    └── DiffRequest.java / DiffResponse.java

src/main/resources/
├── mapper/QuantStrategyMapper.xml
├── mapper/QuantStrategyVersionMapper.xml
└── templates/pages/strategy/
    ├── list.html          → strategy-list.html 内容
    ├── editor.html        → strategy-editor.html 内容
    ├── versions.html      → strategy-versions.html 内容
    ├── universe-config.html → universe-config.html 内容
    └── rule-tree-debug.html → rule-tree-debug.html 内容
```

### 4.3 关键流程

#### 流程 A：创建策略 + 保存版本

```
用户在 strategy-editor.html 填写表单
  → POST /api/strategies
      │
      ▼
  StrategyService.create()
    1. 生成 strategy_key（前端未填时：slugify(策略名称) + 随机 4 位；保证唯一）
    2. validateRuleTreeJSON(buy_rules) → 检查所有 factorKey 在 FactorRegistryCache
         → 不合法则抛 BusinessException(ErrorCode.BAD_REQUEST, "无效因子引用")
    3. 同法校验 sell_rules 中的 compare 节点；特殊节点 stop_loss / take_profit /
         max_holding_days 做独立校验（percent/days 为正数）
    4. 写入 quant_strategy(status=DRAFT)
    5. 若用户勾选"立即保存为版本 1"，写入 quant_strategy_version(version=1)
    6. 返回 { id, strategyKey, status }
  ↓
前端跳转到列表页或继续编辑
```

#### 流程 B：更新策略 + 保存为新版本

```
用户在 editor.html 底部点击「保存为版本 N+1」
  → POST /api/strategies/{id}/versions  { changeNote, backtestId(可选) }
      │
      ▼
  StrategyService.saveVersion(id, changeNote, backtestId)
    1. 从 quant_strategy 按 id 查当前规则快照
    2. SELECT MAX(version) FROM quant_strategy_version WHERE strategy_id=id
         → 若 NULL 则 v=1，否则 v = max+1
    3. 把当前 buy_rules / sell_rules / position_sizing / universe / pre_filters
         连同 changeNote 写入版本表
    4. 若 backtestId 非空 → 存储为本次版本的验证回测关联
    5. 返回 { strategyId, version, createdAt }
```

#### 流程 C：版本回滚

```
用户在 versions.html 选择 v_old，点击「回滚」
  → POST /api/strategies/{id}/versions/{v_old}/rollback
      │
      ▼
  StrategyService.rollbackVersion(id, v_old)
    1. 从版本表取出该 v_old 的 JSON 快照
    2. 更新主表 buy_rules = 该版本 buy_rules，...（含 universe/pre_filters）
    3. 新建一个新版本 v_new，changeNote="回滚到版本 {v_old}"
    4. 不删除 v_old，时间线保留
```

#### 流程 D：规则解析与因子提取（调试面板 + 回测前置步骤）

```
rule-tree-debug.html 粘贴 JSON 后点击「校验并提取」
  → POST /api/strategies/parse
      │
      ▼
  StrategyService.parse(json)
    1. Java 侧基础校验：顶层 keys 是否完整（buy_rules 必须有 operator + conditions）
    2. Java 侧 factorKey 合法性快速校验（遍历叶子，与 FactorRegistryCache 对比）
    3. 调用 Python POST /api/compute/strategy/parse（HTTP JSON）
    4. 接收响应：去重 factor_specs 列表、param_paths 列表、可选的表达式树摘要
    5. 返回给前端渲染「因子引用统计」+「参数清单」区块
```

#### 流程 E：信号模拟（辅助用户验证规则是否符合预期）

```
rule-tree-debug.html 选择 ts_code=xxx + trade_date=yyyyMMdd + 粘贴策略 JSON
  → POST /api/strategies/debug/simulate
      │
      ▼
  StrategyService.simulate(tsCode, tradeDate, strategyJSON)
    1. Java 从 SQLite daily_quote 拉取最近 N 个交易日 OHLCV（N = 规则树最大 lookback hint + 安全余量；默认 120）
    2. 调用 Python POST /api/compute/strategy/simulate，传 OHLCV + JSON
    3. Python 端：用 RuleParser 解析，结合传入因子结果，返回每一个 compare 节点在指定 bar 的布尔值
    4. 返回给前端，展示「每个条件的命中/未命中」以及最终 AND/OR 结果
```

### 4.4 类设计 / 接口契约

#### 4.4.1 StrategyService 核心方法签名

```java
public interface StrategyService {

    /** 创建新策略（前端 POST /api/strategies） */
    QuantStrategyDO create(StrategyCreateRequest req);

    /** 查询策略列表（支持关键字 + 状态 + 分类筛选 + 分页） */
    Page<StrategyListResponse> queryList(String keyword, String status, String category,
                                         int page, int size);

    /** 获取单条策略详情（含完整 JSON） */
    StrategyDetailResponse getDetail(Long id);

    /** 更新策略主表（保存草稿） */
    QuantStrategyDO update(Long id, StrategyUpdateRequest req);

    /** 保存版本（把当前主表的规则快照写入版本表） */
    StrategyVersionResponse saveVersion(Long id, String changeNote, Integer backtestId);

    /** 列出策略的版本时间线 */
    List<StrategyVersionResponse> listVersions(Long id);

    /** 获取某一版本的完整快照 */
    StrategyVersionResponse getVersion(Long id, int version);

    /** 回滚到某一版本（写主表 + 新版本快照记录"回滚动作"） */
    StrategyVersionResponse rollbackVersion(Long id, int version);

    /** 更新状态（DRAFT / ACTIVE / ARCHIVED） */
    void updateStatus(Long id, String status);

    /** 删除策略（仅 DRAFT 且未被回测引用时可删，否则报 409 CONFLICT） */
    void delete(Long id);

    /** 校验规则树 JSON（供前端实时校验） */
    void validateRuleTreeJson(String ruleTreeJson);

    /** 调用 Python 解析规则树，返回因子规格 + 参数路径 */
    ParseResponse parse(String ruleTreeJson);

    /** 单 bar 信号模拟 */
    SimulateResponse simulate(String tsCode, String tradeDate, String ruleTreeJson);

    /** JSON diff（Java 侧实现，简单文本 diff 即可） */
    DiffResponse diff(String leftJson, String rightJson);

    /** 估算选股范围覆盖的股票数量（universe-config.html 预览用） */
    int estimateUniverseCount(String universe, String customCodes, String preFiltersJson);
}
```

#### 4.4.2 Rule Parser 数据结构（Java 侧的 DTO 镜像，只用于和 Python 通信）

```java
// Java 侧只做传输；真正解析由 Python 完成
@Data
public class ParseResponse {
    private List<FactorSpec> factorSpecs;        // 去重后的因子引用列表
    private List<ParamPath> paramPaths;          // 所有可调参数路径
    private List<String> warnings;               // 兼容性提示
    private int lookbackHint;                    // 需要的历史 bar 数
}

@Data
public class FactorSpec {
    private String factorKey;                    // 如 "MA" / "MACD"
    private Map<String, Object> params;          // 如 {"timeperiod": 20}
    private Integer outputIndex;                 // 多输出指标取第几列；null 表示单输出
    private String resultKey;                    // 002 PRD §9.3 命名：factorKey_paramValue / factorKey_outputLabel
}

@Data
public class ParamPath {
    private String path;                         // JSON path，如 "buy_rules.conditions[0].left.params.timeperiod"
    private Object defaultValue;                 // 当前 JSON 中该路径的值
    private String paramName;                    // 人类可读名称（前端展示用）
}
```

### 4.5 与现有系统的集成点

| 集成点 | 说明 |
|--------|------|
| **FactorRegistryCache** | 复用 002 因子库的 Java 缓存。保存策略时校验所有 `factorKey` 是否在 registry 中存在；不存在或拼写不一致时返回 `BAD_REQUEST` |
| **StockBasicService** | `estimateUniverseCount()` 内部调用 `stockBasicService.queryLocal()`：按 INDEX_300/INDEX_500/WATCHLIST/CUSTOM 过滤；按 pre_filters 条件剔除；返回预估的股票数量 |
| **DailyQuoteService** | `simulate()` 内部调用 `dailyQuoteService.queryLocal(tsCode, startDate, endDate)`，取最近 120 根 bar 的 OHLCV 传给 Python 做信号计算 |
| **user_id 维度隔离** | 目前仅管理员使用，暂不做多用户隔离；但 `quant_strategy` 表预留 `created_by` 字段，便于后续扩展 |
| **PageController 新增路由** | 在现有 `PageController` 中添加 `/strategy/list`、`/strategy/editor`、`/strategy/versions`、`/strategy/universe-config`、`/strategy/rule-tree-debug` 5 个路由 |
| **不新增定时任务** | 策略表只在用户操作时写入，不需要定时任务 |
| **不新增缓存** | 策略列表 / 详情数据量小（预期单用户 < 100 条），直接查 SQLite；因子注册表复用 002 的 FactorRegistryCache |
| **不新增枚举** | 复用已有枚举机制；状态 `DRAFT / ACTIVE / ARCHIVED` 存 TEXT 字符串，Java 侧以 `StrategyStatus` 常量校验；分类 `TREND / MEAN_REVERT / MOMENTUM / CUSTOM / SCREEN_DRAFT` 同样存 TEXT |
| **不新增 Tushare 调用** | 所有行情/基础信息走现有 stock_basic / daily_quote 表 |
| **权限** | `/api/strategies/debug/*` 需登录但不强制 admin；写接口（create/update/version/save/rollback/status/delete）**全部需 `@RequireAdmin`** |

### 4.6 异常与错误码

| 场景 | HTTP 状态 | code | message | 复用/新增 |
|------|----------|------|---------|-----------|
| 创建策略时必填字段缺失 | 400 | 400 | `请求参数错误` | 复用 `BAD_REQUEST` |
| strategy_key 与已有冲突 | 409 | 409 | `策略代码已存在` | 复用 `CONFLICT` |
| 规则树中引用了 registry 不存在的因子 | 400 | 400 | `规则树引用了未注册的因子: xxx` | 复用 `BAD_REQUEST`，message 自定义 |
| 保存版本时 JSON 与 JSON parse 失败 | 400 | 400 | `规则树 JSON 格式错误` | 复用 `BAD_REQUEST` |
| 策略 id 不存在 | 404 | 404 | `策略不存在` | 复用 `NOT_FOUND` |
| 版本号不存在 | 404 | 404 | `版本不存在` | 复用 `NOT_FOUND` |
| 尝试删除被回测引用的策略 | 409 | 409 | `该策略已被回测引用，无法删除` | 复用 `CONFLICT` |
| 尝试回滚为与当前相同的版本 | 409 | 409 | `不能回滚到当前正在使用的版本` | 复用 `CONFLICT` |
| simulate 调用时 ts_code 在 daily_quote 没有数据 | 400 | 400 | `tsCode 无行情数据` | 复用 `BAD_REQUEST` |
| Python 计算服务不可达（Connection refused） | 500 | 500 | `计算服务不可用` | 新增 `COMPUTE_UNAVAILABLE`（不扩展 ErrorCode，直接在 ApiResponse 写 code=500, message=文本） |

> 说明：本模块**不扩展 `ErrorCode.java`** 新增枚举。所有错误场景复用 BAD_REQUEST / NOT_FOUND / CONFLICT / 500 基础错误，message 动态填入。无需新增枚举项。

### 4.7 复用清单（基于实时扫描结果）

| 类别 | 现有资源（来自实时扫描） | 复用决策 | 说明 |
|------|---------------------|---------|------|
| **表** | `sys_user`, `sys_watchlist`, `stock_basic`, `trade_cal`, `daily_quote`, `adj_factor`, `dividend` | **全部复用**（只读取，不改表结构）；新建 `quant_strategy`, `quant_strategy_version` 两张表 | stock_basic 用于选股范围预览；daily_quote 用于 simulate 拉取 OHLCV；watchlist 用作 universe=WATCHLIST 场景的选股来源 |
| **接口** | `KlineController` `/api/kline/{stockCode}`, `StockBasicController` `/api/stocks/*`, `SearchController` `/api/search/*`, `WatchlistController` `/api/watchlist` | **不直接在路径上复用**，但 StrategyService 内部通过 `StockBasicService` / `DailyQuoteService` / `WatchlistService` 间接调用 Mapper；前端路由（Thymeleaf）由 `PageController` 扩展 5 个新路径；REST 新增 `/api/strategies` + `/api/strategies/debug/*`，与现有 `/api/tushare/...` 风格一致 |
| **错误码** | `BAD_REQUEST(400)`, `UNAUTHORIZED(401)`, `FORBIDDEN(403)`, `NOT_FOUND(404)`, `CONFLICT(409)`, `USER_EXISTS(1001)`, `STOCK_NOT_FOUND(1004)` | **全部复用**；不新增枚举项。message 按业务场景自定义 |
| **通用返回** | `ApiResponse.success(data)` / `throw new BusinessException(ErrorCode.XXX)` | **全部复用**；所有 REST Controller 返回 `ApiResponse<T>` |
| **Service 层风格** | `@Service @RequiredArgsConstructor @Slf4j`，Lambda `toEntity/toDTO` 转换，`insertOrReplaceBatch` 批量 upsert | **全部复用**；StrategyServiceImpl 完全按此模板实现 |
| **Mapper XML** | `src/main/resources/mapper/*.xml` 风格：`INSERT OR REPLACE INTO table (col1, col2) VALUES <foreach>` | **复用**；QuantStrategyMapper.xml / QuantStrategyVersionMapper.xml 沿用同一模式 |
| **配置** | `application.yml` + `application-dev.yml` / `prod.yml` | **复用**；在 `application.yml` 中新增 `python.compute.url` 指向 `http://127.0.0.1:8000`（与 PRD §3.1 对齐），但该配置可能已在 002 因子库中存在，只需沿用 |
| **前端框架** | Bootstrap 5 + ECharts + 原生 JS；模板放在 `templates/pages/` | **复用**；策略相关页面放在 `templates/pages/strategy/` 子目录，沿用同一 CSS 变量与布局骨架 |

---

## 5. 验收要点

- [ ] P0：通过 `POST /api/strategies` 可成功创建记录，`strategy_key` 重复时返回 409（对应 prototype/strategy-editor.html 的「保存草稿」按钮）
- [ ] P0：`POST /api/strategies/{id}/versions` 写入版本表并返回递增的 version 号（对应 editor.html 底部「保存为版本 N+1」按钮）
- [ ] P0：`GET /api/strategies/{id}/versions` 列出完整时间线，每个版本含 buy_rules / sell_rules / position_sizing 等 JSON 字段（对应 strategy-versions.html）
- [ ] P0：`POST /api/strategies/{id}/versions/{v}/rollback` 能将旧版本规则回写主表，并新建一条"回滚记录"新版本
- [ ] P0：规则树中的 `factorKey` / `params` / `outputIndex` 与 `GET /api/factors/registry` 结果对齐，非法因子在保存时被 `validateRuleTreeJson` 拒绝
- [ ] P0：`POST /api/strategies/debug/parse` 能返回去重的 `factorSpecs` 列表和 `paramPaths` 列表（对应 rule-tree-debug.html「提取因子/参数」按钮）
- [ ] P1：`POST /api/strategies/debug/simulate` 能在给定 ts_code + trade_date 下返回每个 compare 节点的布尔命中情况
- [ ] P1：`POST /api/strategies/{id}/status` 能在 DRAFT / ACTIVE / ARCHIVED 间切换（对应 strategy-list.html 卡片上的「归档/恢复」）
- [ ] P1：`POST /api/strategies/debug/diff` 能对两段策略 JSON 做文本行级 diff，供版本对比页展示
- [ ] P1：`estimateUniverseCount(universe, customCodes, preFilters)` 能返回符合条件的股票数，供 universe-config.html 预览用
- [ ] 权限：所有写接口 `/api/strategies/*`（POST/PUT/DELETE）需 `@RequireAdmin` 拦截
- [ ] 数据一致性：quant_strategy 表的 buy_rules / sell_rules / universe / pre_filters / position_sizing JSON 在保存时经过 `validateRuleTreeJson` 基础校验
- [ ] Tushare / Python 集成：simulate 能从 daily_quote 读取历史 OHLCV（120 天），并通过 HTTP 调用 Python `/api/compute/strategy/simulate` 返回结果；Python 服务不可达时返回 500 `计算服务不可用`

---

## 6. 风险与 TODO

| 项 | 风险 / 未明确 |
|----|--------------|
| **Python 计算服务地址** | PRD §3.1 要求 `http://127.0.0.1:8000`，但生产环境可能需要独立部署与端口配置；本设计在 `application.yml` 中新增 `python.compute.url` 占位，便于运维调整 |
| **策略 JSON Schema 精确版本** | PRD §8 / Schema 文档定义了 3 层结构（逻辑节点 + compare 节点 + 表达式节点）以及 3 种特殊卖出规则；前端编辑/校验需严格遵循这套 Schema 定义。若后续 Python 端扩展（如新增 `ref` 表达式节点用于动态止损），Java 校验需同步扩展允许 key 白名单。本设计**采用白名单校验**：只允许 `operator / conditions / type / left / right / comparator / value / factor / params / output_index / inputs / op` 及特殊卖出规则的 `percent / days`，其余 key 报 400 |
| **选股范围覆盖** | `INDEX_300 / INDEX_500` 的成分股列表需要一个可靠来源；当前 `stock_basic` 表无 `is_zz500` 等列。**TODO**：方案 A（推荐）：在 `stock_basic` 表新增 `is_index_300 / is_index_500` 两个 INTEGER 列，由 data-init 任务定期刷新；方案 B：策略侧存 `universe_filter` 自定义股票代码列表，用户手动维护。本设计以方案 A 为默认（由 data-init 模块扩展，不在本设计范围内），同时保留方案 B（`universe=CUSTOM_CODES` + `universe_filter` 字段存代码列表 JSON）作兜底 |
| **止损/止盈/最大持仓的执行语义** | 由 PRD §8.7 精确定义：止损用当日 low 与入场价 × (1-percent) 比较；止盈用当日 high 与入场价 × (1+percent) 比较；条件卖出用 close；max_holding_days 不判断价格。Python 端需维护入场价与持仓天数的状态；Java 端不管理这些状态，只存 JSON。**TODO**：与 004 回测中心确定 Python 端持仓状态的传递方式 |
| **SCREEN_DRAFT 分类** | 选股草稿场景复用 quant_strategy 表，status=DRAFT，category=SCREEN_DRAFT。由于选股草稿往往只有 buy_rules 而无 sell_rules / position_sizing，策略保存和 JSON schema 校验要支持**sell_rules / position_sizing 可为空**（仅当 category=SCREEN_DRAFT 时），否则缺失会报 400 |
| **数据库迁移** | 本模块 4 份设计文档中的 schema.sql 仅定义 `quant_strategy` + `quant_strategy_version` 两张新表，不改动现有表。若后续需要 `is_index_300 / is_index_500` 列，由独立 ALTER 任务追加到 `stock_basic`，归属于选股模块或数据中台 |
| **无前端视觉稿** | 本模块原型图采用 Bootstrap 5 卡片式布局，与 `strategy-list.html` / `strategy-editor.html` 等 HTML 文件一致；实际页面风格（主色、字体、图标大小）与现有 Stock Watcher 页面对齐（通过引用同一套 CSS 变量实现） |
| **多用户/角色** | 当前系统只有 admin 一个角色，@RequireAdmin 足以覆盖。若未来引入多用户，需要在 `quant_strategy` 表加 `created_by` / `owner_user_id` 字段，本设计已预留。但当前实现按单用户场景简化，不做 owner 过滤 |
| **Python 侧接口稳定性** | `/api/compute/strategy/parse` 和 `/api/compute/strategy/simulate` 由 Python 端提供；Java 侧调用使用 RestTemplate 或 OkHttp，超时 5s、失败重试 0 次（用户操作对延迟敏感，出错即返回 500）。**TODO**：定义 Python 端 Pydantic 请求/响应模型（见 04-http-api.md 的「Java ↔ Python 协议」小节），确保字段名一致 |

---

## 7. 自检记录（本次填写）

| 检查项（从全局 checklist 中摘取本次相关项） | 结果 |
|------------------------------------------|------|
| 所有表名 / 字段名为 snake_case（quant_strategy / buy_rules / strategy_key / ...） | ✅ |
| 所有 JSON 字段（请求/响应）为 camelCase（strategyKey, buyRules, changeNote, createdAt, ...） | ✅ |
| SQLite 数据类型正确（INTEGER / TEXT / REAL），无 MySQL 专有类型 | ✅ |
| 无重复的 Controller 路径：`/api/strategies` 未被现有 Controller 使用 | ✅ |
| 不新增 `ErrorCode` 枚举项，全部复用 BAD_REQUEST / NOT_FOUND / CONFLICT / 500 | ✅ |
| 01-main-design.md 头部包含「参考资料索引」，引用了 PRD / Schema / 所有原型图路径 | ✅ |
| 模块划分的 Java 包路径与现有项目一致（`com.arthur.stock.controller / service / mapper / model / dto`） | ✅ |
| Service 层遵循 `@Service @RequiredArgsConstructor @Slf4j` 风格 | ✅ |
| 如涉及权限，已区分哪些接口需要 `@RequireAdmin` | ✅ |
| 未编造新的 Tushare 接口；行情/基础数据来源为现有 stock_basic / daily_quote | ✅ |

