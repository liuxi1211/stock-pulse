# 用户个性化功能设计

> **覆盖范围**：个性化体系总览、自选股与持仓管理、智能盯盘引擎、定时任务系统、消息通知中心、个性化统计模块、数据模型与索引设计
> **来源**：由 `ai-stock-analysis-platform-plan.md` 第六章扩展优化而成
> **关联文档**：02 系统架构、03 富交互 Chat 前端设计、07 数据架构与数据库设计

---

## 一、个性化体系总览

### 1.1 功能全景

个性化体系是 C 端用户应用区别于纯对话工具的核心壁垒。规划书将整个体系划分为四个功能模块和一个统一推送枢纽，外加报告中心作为分析资产的归集层。

```
┌──────────────────────────────────────────────────────────────────┐
│                      用户个性化体系                               │
│                                                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────────┐ │
│  │ 自选股    │  │ 持仓管理  │  │ 盯盘监控  │  │ 定时任务          │ │
│  │ 管理     │  │          │  │ 引擎     │  │                  │ │
│  └─────┬────┘  └─────┬────┘  └─────┬────┘  └────────┬─────────┘ │
│        │             │             │                 │           │
│        └─────────────┴─────────────┴─────────────────┘           │
│                              │                                     │
│                    ┌─────────▼─────────┐                           │
│                    │  消息通知中心      │                           │
│                    │  (统一推送枢纽)    │                           │
│                    └─────────┬─────────┘                           │
│                              │                                     │
│              ┌───────────────┼───────────────┐                     │
│              │               │               │                     │
│         ┌────▼────┐   ┌─────▼─────┐   ┌────▼─────┐               │
│         │ 站内消息  │   │ 邮件推送   │   │ 微信推送  │               │
│         └─────────┘   └───────────┘   └──────────┘               │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────────┐│
│  │                    报告中心（统一管理）                         ││
│  │  按股票分类 │ 按类型分类 │ 按时间分类 │ 收藏 │ 对比 │ 导出     ││
│  └──────────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────┘
```

四个模块的定位各有侧重。**自选股管理**是用户投资标的的登记簿，持仓管理在此基础上叠加了买入成本和数量的维度。**盯盘引擎**解决"没时间盯盘但怕错过"的痛点，将用户的监控意图转化为可被调度系统精确执行的规则。**定时任务系统**解决"希望定期收到分析报告"的需求，用 Quartz 调度器驱动 Graph 分析流程。消息通知中心则是所有触发事件的统一出口，负责把盯盘触发和任务结果推送到用户能感知到的地方。

### 1.2 模块间数据流

四个功能模块不是孤立的孤岛，而是一条从"标的登记"到"事件感知"再到"消息触达"的完整链路。理解这条链路对后续各模块的接口设计至关重要。

```
用户操作                  数据层                         调度层                    推送层
──────                  ──────                        ──────                    ──────

添加自选股 ──────> user_watchlist
                      │
                      │  选股进入监控范围
                      ▼
创建盯盘规则 ──────> monitor_rule ──────────> Quartz 调度引擎
(对话/页面)            │                    │
                      │                    │ 盘中/盘后定时检查
                      │                    ▼
                      │              规则触发判定
                      │                    │ 命中
                      │                    ▼
                      │              事件处理引擎
                      │              ├─ 取数（行情/财务/资金）
                      │              ├─ AI 简评生成
                      │              └─ 富媒体消息组装
                      │                    │
                      │                    ▼
                      │              push_notification ────> WebSocket 实时推送
                      │                    │                 ├─ 在线：站内消息
                      │                    │                 └─ 离线：邮件兜底
                      │                    ▼
创建定时任务 ──────> user_task ──────────> Quartz 定时触发
(对话/页面)            │                    │
                      │                    ▼
                      │              任务执行引擎
                      │              ├─ 取数（自选股/板块/持仓）
                      │              ├─ Graph/AI 生成报告
                      │              ├─ analysis_report 落库
                      │              └─ push_notification 推送
                      │                    │
                      ▼                    ▼
user_portfolio <─── 持仓录入           user_task_log（执行记录）
(手动录入)            │
                      │ 实时行情推送
                      ▼
                 WebSocket 价格推送 ──> 侧边栏自选股面板更新
```

这条链路有三个关键交汇点。第一个是**自选股与盯盘规则的交汇**：用户添加自选股后，可以直接基于自选股创建盯盘规则，避免重复输入股票代码。第二个是**盯盘规则与定时任务的交汇**：财务事件类规则本质上也是一种"等待型任务"，只是触发条件由数据驱动而非时间驱动。第三个是**所有模块与通知中心的交汇**：无论是盯盘触发、任务结果还是系统通知，都统一写入 `push_notification` 表，由通知中心统一推送。

### 1.3 用户数据隔离设计

C 端应用面向公网多用户，数据隔离是安全底线。所有个性化业务表都以 `user_id` 作为第一隔离维度，任何查询都必须携带 `user_id` 条件，不存在跨用户的全量查询入口。

**隔离策略分三层**。第一层是数据库层，所有用户业务表都建有 `(user_id)` 索引，MyBatis-Plus 的查询 Wrapper 在 Service 层强制注入当前登录用户的 `user_id`，防止因 Controller 层疏忽导致越权查询。第二层是缓存层，Redis 中用户维度的数据（自选股列表、盯盘规则缓存、WebSocket 会话）都以 `user:{userId}:` 作为 key 前缀，不同用户的数据在缓存空间上物理隔离。第三层是 WebSocket 推送层，消息推送时根据 `user_id` 查找对应的 WebSocket Session 集合，消息只发往该用户的连接。

```
数据隔离三层模型

┌─────────────────────────────────────────────────┐
│  WebSocket 推送层                                 │
│  userId=1001 的消息只发往 Session_A / Session_B  │
│  userId=1002 的消息只发往 Session_C              │
├─────────────────────────────────────────────────┤
│  Redis 缓存层                                    │
│  user:1001:watchlist  /  user:1001:rules        │
│  user:1002:watchlist  /  user:1002:rules        │
├─────────────────────────────────────────────────┤
│  MySQL 数据层                                    │
│  WHERE user_id = #{currentUserId} 强制注入       │
│  (user_id) 索引保证查询效率                       │
└─────────────────────────────────────────────────┘
```

对于 Quartz 调度的任务和规则，JobDataMap 中存储 `userId` 字段，执行时通过 `userId` 过滤数据范围。盯盘引擎的批量检查也按 `userId` 分组执行，避免不同用户的规则在同一个检查批次中交叉。

---

## 二、自选股与持仓管理

### 2.1 自选股管理设计

自选股是用户在平台上最基础的个性化资产。用户可以通过两种入口管理自选股：一种是对话式入口，在聊天中说"加个自选茅台"或"把宁德时代从自选里删掉"，AI 通过 Function Calling 调用 `addWatchlist` / `removeWatchlist` 工具完成操作；另一种是页面操作入口，在自选股管理页面通过表单和按钮直接增删改查。

自选股支持**分组管理**。用户可以创建自定义分组（如"白酒""新能源""长期关注"），每只自选股归属一个分组，默认分组为"默认"。分组本身不是独立表，而是 `user_watchlist` 表的 `group_name` 字段，用户可以重命名分组、将股票在分组间移动。每只股票在同一用户的同一分组下唯一，但同一股票可以出现在不同分组中（如同时加入"白酒"和"长期关注"）。

左侧边栏的自选股快捷面板是自选股功能的高频触达入口。面板默认折叠展示"默认"分组的前 5 只股票的实时价格和涨跌幅，点击展开显示全部分组和全部股票。面板的数据通过 WebSocket 实时推送更新，无需手动刷新。

### 2.2 自选股 API 设计

自选股的 API 设计遵循 RESTful 资源导向原则，URL 使用 kebab-case 命名。所有接口的 `user_id` 从 HttpSession 中获取，不暴露在 URL 或请求参数中。

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 查询自选股列表 | GET | `/api/watchlist` | 支持 `group` 参数筛选分组，返回带实时行情的列表 |
| 添加自选股 | POST | `/api/watchlist` | 请求体包含 `tsCode`、`groupName`、`targetPrice`、`note` |
| 更新自选股 | PUT | `/api/watchlist/{id}` | 更新备注、目标价、排序序号 |
| 删除自选股 | DELETE | `/api/watchlist/{id}` | 单条删除 |
| 批量删除自选股 | POST | `/api/watchlist/batch-delete` | 请求体为 `id` 列表 |
| 调整排序 | POST | `/api/watchlist/sort` | 请求体为 `{id, sortOrder}` 列表，批量更新排序 |
| 移动到分组 | POST | `/api/watchlist/{id}/move` | 请求体包含目标 `groupName` |
| 查询分组列表 | GET | `/api/watchlist/groups` | 返回当前用户所有分组名称及各分组股票数量 |
| 重命名分组 | PUT | `/api/watchlist/groups` | 请求体包含 `oldName`、`newName`，批量更新该分组下所有记录 |

查询自选股列表时，后端先从 `user_watchlist` 表读取该用户的自选股记录，再批量查询这些股票的最新行情（从 `daily_quote` 表或 Redis 行情缓存中获取），最终组装为带有实时价格、涨跌幅、成交额的列表返回。批量行情查询走 Redis 缓存优先、数据库兜底的策略，避免每次查询都对每只股票单独查库。

以下是添加自选股的请求体和响应体设计。

```json
// POST /api/watchlist 请求体
{
  "tsCode": "600519.SH",
  "groupName": "白酒",
  "targetPrice": 1800.00,
  "note": "长期关注，等估值回落"
}

// POST /api/watchlist 响应体
{
  "code": 200,
  "data": {
    "id": 1024,
    "tsCode": "600519.SH",
    "stockName": "贵州茅台",
    "groupName": "白酒",
    "targetPrice": 1800.00,
    "note": "长期关注，等估值回落",
    "sortOrder": 0,
    "createdAt": "2026-08-06 14:30:00"
  }
}
```

排序设计上，`sort_order` 字段为整数，值越小越靠前。新增自选股时默认追加到当前分组末尾（`sort_order` 取当前最大值加 1）。前端拖拽排序后，一次性提交全量 `{id, sortOrder}` 列表，后端批量更新。

### 2.3 持仓管理计算逻辑

持仓管理是模拟持仓，用户手动录入买入信息（股票代码、买入价、数量、买入日期），系统自动计算盈亏和收益指标。所有计算在后端完成，前端只负责渲染。

**盈亏计算**是持仓管理的核心。单只持仓的浮动盈亏等于当前市值减去买入成本，公式为 `profit = (current_price - buy_price) * quantity`。收益率等于浮动盈亏除以买入成本，公式为 `return_rate = (current_price - buy_price) / buy_price * 100%`。当前价从 `daily_quote` 表的最新一条记录获取，如果当日未收盘则取最近一个交易日的收盘价。

持仓占比反映用户的仓位集中度。单只持仓的占比等于该持仓的当前市值除以所有持仓的总市值，公式为 `weight = (current_price * quantity) / sum(current_price * quantity for all holdings) * 100%`。总市值即所有持仓当前市值之和。

行业分布聚合用于持仓的可视化展示。系统根据每只持仓股票的申万一级行业分类（从 `stock_basic` 表的 `industry` 字段获取），将持仓按行业分组汇总市值和占比，最终输出为饼图数据。同理，持仓估值雷达图将所有持仓的 PE、PB、ROE 指标汇总后做横向对比，数据来源是 `daily_basic` 表和 `fina_indicator` 表。

以下是持仓概览的响应结构示例。

```json
// GET /api/portfolio 响应体（概览）
{
  "code": 200,
  "data": {
    "totalCost": 150000.00,
    "totalValue": 165000.00,
    "totalProfit": 15000.00,
    "totalReturnRate": 10.00,
    "holdings": [
      {
        "id": 1,
        "tsCode": "600519.SH",
        "stockName": "贵州茅台",
        "buyPrice": 1600.00,
        "quantity": 50,
        "buyDate": "2026-06-01",
        "currentPrice": 1705.00,
        "profit": 5250.00,
        "returnRate": 6.56,
        "weight": 51.67,
        "industry": "食品饮料"
      }
    ],
    "industryDistribution": [
      { "industry": "食品饮料", "value": 85250.00, "weight": 51.67 },
      { "industry": "银行",     "value": 56000.00, "weight": 33.94 },
      { "industry": "电力设备",  "value": 23750.00, "weight": 14.39 }
    ]
  }
}
```

持仓变动时的自动预警是持仓管理的延伸能力。当某只持仓的浮动亏损超过用户设定的阈值（默认 10%）时，系统自动生成一条预警通知写入 `push_notification` 表，通过 WebSocket 推送给用户。预警逻辑通过盯盘引擎的价格监控规则实现，用户录入持仓后自动为其创建一条"浮亏超过阈值"的价格监控规则，无需手动配置。

### 2.4 自选股实时行情推送机制

自选股快捷面板需要展示实时价格和涨跌幅，更新方式有两种选择：WebSocket 服务端推送和前端定时轮询。

**选择 WebSocket 推送**而非定时轮询，基于三方面考量。第一是实时性，轮询的间隔通常为 3-5 秒，用户看到的始终是几秒前的数据，而 WebSocket 推送可以在行情更新后秒级触达。第二是服务端压力，假设 1000 个在线用户各有 10 只自选股，轮询模式下每秒产生约 3000-5000 次 HTTP 请求，WebSocket 模式下服务端只需在行情数据变化时主动推送，空闲时段零流量。第三是电池友好，移动端轮询会持续唤醒网络模块，WebSocket 长连接的心跳频率远低于轮询频率。

行情数据的来源是 B 端 ERP 系统的定时采集任务。ERP 每日 16:00 收盘后拉取 Tushare 日线数据写入 `daily_quote` 表，但这只是日频数据。盘中实时行情的获取需要对接 Tushare 的实时行情接口（`rt_quote`），由 ERP 侧定时拉取后写入 Redis 行情缓存，C 端应用从 Redis 读取后通过 WebSocket 推送给前端。

```
实时行情推送链路

ERP 定时任务                Redis 缓存               C 端后端                前端
────────────              ──────────              ──────────              ────

每 3 秒拉取 rt_quote ──> 行情缓存写入 ──> 行情变更监听 ──> WebSocket 推送 ──> 侧边栏更新
                                         (Redis Keyspace
                                          Notifications)
```

行情变更的感知采用 **Redis Keyspace Notifications** 机制。ERP 写入行情缓存时触发 key 过期或修改事件，C 端后端订阅这些事件后，根据 key 中的 `ts_code` 反查哪些用户关注了这只股票，然后向这些用户的 WebSocket 连接推送更新。这种设计避免了 C 端后端定时轮询 Redis 的开销，实现真正的"数据驱动推送"。

对于离线用户（WebSocket 未连接），行情数据不推送，用户下次上线时从 `daily_quote` 表加载最新收盘价即可。实时行情推送仅对在线用户有意义，不需要离线补发。

---

## 三、智能盯盘引擎

### 3.1 盯盘规则类型总览

规划书定义了 8 种盯盘规则类型，覆盖价格、技术面、资金面、事件面四个维度。每种规则类型的触发条件、数据源和检查频率各不相同，下表是总览。

| 规则类型 | 代号 | 触发条件概述 | 数据源 | 检查频率 |
|----------|------|-------------|--------|----------|
| 价格监控 | PRICE | 突破/跌破目标价 | `daily_quote` / 实时行情 | 盘中每分钟 |
| 涨跌幅监控 | CHANGE | 日涨跌幅超阈值 | `daily_quote` / 实时行情 | 盘中每分钟 |
| 技术指标 | TECHNICAL | MA 金叉/死叉、MACD 信号 | `daily_quote` + talib 计算 | 日终收盘后 |
| 资金流向 | MONEYFLOW | 主力净流入超阈值 | `moneyflow` | 盘中每小时 |
| 财务事件 | FINANCIAL | 财报披露/预告/快报 | `income` / `fina_indicator` / 公告接口 | 盘后每日 |
| 公告事件 | ANNOUNCEMENT | 重大公告/分红/解禁 | Tushare 公告接口 | 盘后每日 |
| 龙虎榜 | TOP_LIST | 上榜龙虎榜 | `top_list` | 盘后每日（龙虎榜发布后） |
| 北向资金 | HK_HOLD | 沪深港通持股变动超阈值 | `hk_hold` | 盘中每小时 |

### 3.2 各规则类型触发逻辑

以下为每种规则类型的精确判定逻辑，`rule_params` 字段存储规则参数的 JSON 字符串。

**价格监控（PRICE）**

用户设定目标价和方向（向上突破或向下跌破），系统在盘中每分钟检查最新价格是否越过阈值。

```python
# rule_params 示例: {"direction": "below", "target_price": 1700.00}
# direction: "above" = 突破向上, "below" = 跌破向下

def check_price_rule(rule, current_price):
    target = rule["params"]["target_price"]
    direction = rule["params"]["direction"]

    if direction == "above" and current_price >= target:
        return triggered(reason=f"价格突破 {target}")
    if direction == "below" and current_price <= target:
        return triggered(reason=f"价格跌破 {target}")
    return not_triggered
```

数据源是 Tushare 的实时行情接口，经 ERP 写入 Redis 缓存后供 C 端读取。检查频率为交易日盘中每分钟一次（9:30-11:30, 13:00-15:00），非交易日和盘后不检查。

**涨跌幅监控（CHANGE）**

用户设定涨跌幅阈值（如 3%），系统计算当日涨跌幅是否超过阈值。涨跌幅的计算基准是前一日收盘价。

```python
# rule_params 示例: {"threshold": 3.0, "direction": "both"}
# direction: "up" = 仅涨幅, "down" = 仅跌幅, "both" = 双向

def check_change_rule(rule, current_price, pre_close):
    threshold = rule["params"]["threshold"]
    direction = rule["params"]["direction"]
    change_pct = (current_price - pre_close) / pre_close * 100

    if direction in ("up", "both") and change_pct >= threshold:
        return triggered(reason=f"涨幅 {change_pct:.2f}% 超过阈值 {threshold}%")
    if direction in ("down", "both") and change_pct <= -threshold:
        return triggered(reason=f"跌幅 {change_pct:.2f}% 超过阈值 {threshold}%")
    return not_triggered
```

数据源同价格监控，`pre_close` 从 `daily_quote` 表的前一交易日记录获取。检查频率与价格监控一致，盘中每分钟一次。为避免同一规则在同一方向上反复触发，规则触发后进入冷却期（当日不再重复检查同方向），冷却期在 `monitor_rule` 表的 `last_triggered` 字段记录。

**技术指标（TECHNICAL）**

技术指标类规则在日终收盘后计算，因为需要完整的当日 K 线数据。支持 MA 金叉/死叉和 MACD 金叉/死叉两种子类型。

```python
# rule_params 示例: {"indicator": "MA_CROSS", "fast_period": 5, "slow_period": 20}
# indicator: "MA_CROSS" = MA金叉/死叉, "MACD_CROSS" = MACD金叉/死叉

def check_technical_rule(rule, daily_quotes):
    indicator = rule["params"]["indicator"]

    if indicator == "MA_CROSS":
        fast = MA(daily_quotes, rule["params"]["fast_period"])
        slow = MA(daily_quotes, rule["params"]["slow_period"])
        # 金叉：快线从下方穿越到上方
        if fast[-2] <= slow[-2] and fast[-1] > slow[-1]:
            return triggered(reason="MA 金叉")
        # 死叉：快线从上方穿越到下方
        if fast[-2] >= slow[-2] and fast[-1] < slow[-1]:
            return triggered(reason="MA 死叉")

    if indicator == "MACD_CROSS":
        dif, dea, _ = MACD(daily_quotes)
        if dif[-2] <= dea[-2] and dif[-1] > dea[-1]:
            return triggered(reason="MACD 金叉")
        if dif[-2] >= dea[-2] and dif[-1] < dea[-1]:
            return triggered(reason="MACD 死叉")
    return not_triggered
```

数据源是 `daily_quote` 表的历史收盘价序列，指标计算由 Python 计算服务（FastAPI + talib）完成。检查频率为每个交易日收盘后执行一次（15:30 后），因为需要完整的当日 K 线。计算时取最近 N 天（N 取 fast_period 和 slow_period 的较大值再加 10 天缓冲）的收盘价序列。

**资金流向（MONEYFLOW）**

用户设定主力净流入阈值（如 1 亿元），系统检查当日主力资金净流入是否超过阈值。

```python
# rule_params 示例: {"threshold": 100000000, "direction": "inflow"}
# direction: "inflow" = 净流入超阈值, "outflow" = 净流出超阈值

def check_moneyflow_rule(rule, moneyflow_data):
    threshold = rule["params"]["threshold"]
    direction = rule["params"]["direction"]
    net_mf = moneyflow_data["net_mf_amount"]  # 主力净流入金额

    if direction == "inflow" and net_mf >= threshold:
        return triggered(reason=f"主力净流入 {net_mf/1e8:.2f} 亿元")
    if direction == "outflow" and net_mf <= -threshold:
        return triggered(reason=f"主力净流出 {abs(net_mf)/1e8:.2f} 亿元")
    return not_triggered
```

数据源是 `moneyflow` 表，Tushare 接口为 `moneyflow`（个股资金流向）。检查频率为盘中每小时一次，因为资金流向数据在交易时段内持续更新。盘后做最终确认，以收盘后的数据为准。

**财务事件（FINANCIAL）**

用户设定监听某只股票的财报披露事件，系统每日盘后检查是否有新的财报记录。

```python
# rule_params 示例: {"event_type": "ANNUAL_REPORT"}
# event_type: "ANNUAL_REPORT" / "QUARTERLY_REPORT" / "FORECAST" / "EXPRESS"

def check_financial_rule(rule, last_check_date, current_date):
    event_type = rule["params"]["event_type"]
    # 查询 income / fina_indicator / forecast 表中
    # ann_date 在 (last_check_date, current_date] 区间的新记录
    new_reports = query_financial_events(
        ts_code=rule["ts_code"],
        event_type=event_type,
        start_date=last_check_date,
        end_date=current_date
    )
    if new_reports:
        report = new_reports[0]
        return triggered(
            reason=f"发布{event_type_label(event_type)}",
            data=report
        )
    return not_triggered
```

数据源是 `income`（利润表）、`balancesheet`（资产负债表）、`cashflow`（现金流量表）、`fina_indicator`（财务指标）和 `forecast`（业绩预告）等 Tushare 财务接口。检查频率为盘后每日一次，比对 `ann_date`（公告日期）是否在上次检查日期之后有新增记录。

**公告事件（ANNOUNCEMENT）**

用户设定监听某只股票的公告事件类型（如分红、解禁、增发），系统每日盘后检查新公告。

```python
# rule_params 示例: {"category": "DIVIDEND"}
# category: "DIVIDEND" / "DELIST" / "LOCKUP_EXPIRE" / "REFINANCING" / "ANY"

def check_announcement_rule(rule, last_check_date, current_date):
    category = rule["params"]["category"]
    announcements = query_announcements(
        ts_code=rule["ts_code"],
        start_date=last_check_date,
        end_date=current_date
    )
    if category == "ANY":
        matched = announcements
    else:
        matched = [a for a in announcements if a["category"] == category]
    if matched:
        return triggered(
            reason=f"发布{category_label(category)}公告",
            data=matched[0]
        )
    return not_triggered
```

数据源是 Tushare 的公告接口（`anns` 或 `news`）。检查频率为盘后每日一次。

**龙虎榜（TOP_LIST）**

用户设定监听某只股票是否上榜龙虎榜，系统每日盘后龙虎榜数据发布后检查。

```python
# rule_params 示例: {"reason": "ANY"}
# reason: "ANY" / "PRICE_LIMIT" / "TURNOVER" / "AMOUNT"

def check_top_list_rule(rule, trade_date):
    top_list = query_top_list(trade_date=trade_date, ts_code=rule["ts_code"])
    if top_list:
        return triggered(
            reason=f"上榜龙虎榜（{top_list['reason']}）",
            data=top_list
        )
    return not_triggered
```

数据源是 `top_list` 表，Tushare 接口为 `top_list`（龙虎榜每日明细）。检查频率为盘后每日一次，在龙虎榜数据发布后（通常为 18:00 后）执行。

**北向资金（HK_HOLD）**

用户设定沪深港通持股变动阈值（如持股比例变动超过 0.5%），系统检查北向资金持股变化。

```python
# rule_params 示例: {"threshold_pct": 0.5, "direction": "both"}
# threshold_pct: 持股比例变动百分比阈值

def check_hk_hold_rule(rule, current_hold, prev_hold):
    threshold = rule["params"]["threshold_pct"]
    direction = rule["params"]["direction"]
    hold_change = current_hold["hold_ratio"] - prev_hold["hold_ratio"]

    if direction in ("increase", "both") and hold_change >= threshold:
        return triggered(reason=f"北向持股比例增加 {hold_change:.2f}%")
    if direction in ("decrease", "both") and hold_change <= -threshold:
        return triggered(reason=f"北向持股比例减少 {abs(hold_change):.2f}%")
    return not_triggered
```

数据源是 `hk_hold` 表，Tushare 接口为 `hk_hold`（沪深港通持股明细）。检查频率为盘中每小时一次，因为北向资金数据在交易时段内持续更新。

### 3.3 规则解析引擎

用户通过自然语言创建盯盘规则，需要一个解析引擎将自然语言转化为可执行的结构化规则。规划书提到了"LLM 解析自然语言为结构化监控规则"这一步骤，以下是其完整流程。

```
用户输入: "盯住茅台，跌破1700通知我"
    │
    ▼
┌─────────────────────────────────────────────┐
│  Step 1: 意图识别                             │
│  LLM 判定意图为"创建盯盘规则"                   │
│  调用 createMonitorRule 工具                  │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│  Step 2: 实体抽取 + 参数解析                   │
│  LLM 从自然语言中抽取:                         │
│  ├─ 股票: 茅台 -> 600519.SH                   │
│  ├─ 规则类型: PRICE (价格监控)                 │
│  ├─ 方向: below (跌破)                        │
│  └─ 目标价: 1700.00                          │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│  Step 3: 生成结构化规则 JSON                   │
│  {                                          │
│    "ts_code": "600519.SH",                  │
│    "rule_type": "PRICE",                    │
│    "rule_params": {                         │
│      "direction": "below",                  │
│      "target_price": 1700.00                │
│    },                                       │
│    "push_channels": ["IN_APP"]              │
│  }                                          │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│  Step 4: 规则校验                             │
│  ├─ ts_code 是否存在且有效                     │
│  ├─ rule_type 是否在 8 种类型枚举内             │
│  ├─ rule_params 字段是否完整且合法              │
│  │   (target_price > 0, threshold > 0 等)    │
│  └─ 冲突检测: 是否与已有规则重复                 │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│  Step 5: 返回确认容器给用户                    │
│  展示规则详情，用户点击"确认创建"               │
└──────────────────┬──────────────────────────┘
                   │
                   ▼ (用户确认后)
┌─────────────────────────────────────────────┐
│  Step 6: 规则落库 + Quartz 注册               │
│  ├─ 写入 monitor_rule 表                     │
│  ├─ 根据规则类型创建对应 Trigger              │
│  │   (PRICE/CHANGE -> 每分钟, 盘中)           │
│  │   (TECHNICAL -> 日终, 盘后)               │
│  └─ 返回"已创建"消息给用户                    │
└─────────────────────────────────────────────┘
```

LLM 解析时使用 **Function Calling** 而非自由文本生成。`createMonitorRule` 工具的函数签名中明确定义了 `rule_type` 枚举值和 `rule_params` 的 JSON Schema，LLM 的输出被约束在合法的参数空间内。如果 LLM 无法解析出完整参数（如用户只说了"盯住茅台"但没说具体条件），系统返回一个交互表单容器，引导用户补充缺失的参数。

### 3.4 规则冲突检测

用户在创建规则时可能与已有规则重复。例如用户已经创建了"茅台跌破 1700 通知我"的规则，再次输入相同或高度相似的指令时，系统应检测到冲突并提示用户。

冲突检测的判定逻辑分为两个层次。第一层是**完全重复检测**：新规则的 `ts_code`、`rule_type`、`rule_params` 三个字段与某条已启用规则完全相同，判定为重复。第二层是**语义重复检测**：新规则与已有规则在逻辑上等价但参数表述不同，例如"茅台跌破 1700"和"茅台价格低于 1700"属于同一条规则。

```python
def detect_conflict(user_id, new_rule):
    existing_rules = query_enabled_rules(user_id, new_rule["ts_code"])

    for existing in existing_rules:
        # 完全重复：类型和参数都一致
        if (existing["rule_type"] == new_rule["rule_type"]
                and normalize_params(existing["rule_params"])
                    == normalize_params(new_rule["rule_params"])):
            return ConflictResult(
                type="DUPLICATE",
                existing_rule=existing,
                message="已存在相同的监控规则，是否仍要创建？"
            )

        # 语义重复：价格监控方向相同、阈值接近（差异 < 2%）
        if (existing["rule_type"] == "PRICE"
                and new_rule["rule_type"] == "PRICE"
                and existing["params"]["direction"] == new_rule["params"]["direction"]
                and abs(existing["params"]["target_price"] - new_rule["params"]["target_price"])
                    / new_rule["params"]["target_price"] < 0.02):
            return ConflictResult(
                type="SIMILAR",
                existing_rule=existing,
                message="已存在相似的价格监控规则（目标价 {existing_price}），是否仍要创建？"
            )

    return NoConflict
```

冲突检测结果通过对话中的任务确认容器展示给用户。如果是完全重复，容器提示"已存在相同的监控规则"并提供"仍要创建"和"取消"两个选项。如果是语义重复，容器展示已有规则的详情，让用户自行判断是否需要新建。冲突检测不阻止用户创建，只是提醒，避免用户因遗忘而创建大量重复规则。

### 3.5 盯盘引擎并发控制

当用户量增长后，盯盘引擎面临大量规则同时检查的压力。假设 1000 个用户各创建 5 条规则，总共 5000 条规则，其中价格和涨跌幅类规则每分钟检查一次，意味着每分钟有约 3000 次行情查询。必须设计合理的并发控制策略。

**分批分组调度**是核心策略。Quartz 的 Trigger 不为每条规则单独创建一个 Job，而是按规则类型创建批量 Job。例如价格监控类规则共用一个每分钟触发的 Job，该 Job 一次性加载所有启用的 PRICE 类型规则，批量查询涉及的股票行情，然后逐条判定。这样将 3000 次单独行情查询合并为一次批量查询。

```
并发控制架构

┌──────────────────────────────────────────────────────┐
│  Quartz Scheduler                                    │
│                                                      │
│  ┌─────────────────┐  每分钟触发(盘中)                 │
│  │ PriceCheckJob    │──> 批量加载所有 PRICE 规则        │
│  │                  │──> 批量查询涉及股票的实时行情      │
│  │                  │──> 逐条判定 -> 触发事件           │
│  └─────────────────┘                                │
│                                                      │
│  ┌─────────────────┐  每小时触发(盘中)                 │
│  │ MoneyFlowJob     │──> 批量加载 MONEYFLOW + HK_HOLD  │
│  │                  │──> 批量查询资金流向数据           │
│  │                  │──> 逐条判定 -> 触发事件           │
│  └─────────────────┘                                │
│                                                      │
│  ┌─────────────────┐  每日触发(盘后)                  │
│  │ EndOfDayJob      │──> 批量加载 TECHNICAL + FINANCIAL│
│  │                  │    + ANNOUNCEMENT + TOP_LIST    │
│  │                  │──> 批量查询数据 -> 逐条判定       │
│  └─────────────────┘                                │
└──────────────────────────────────────────────────────┘
```

**行情查询的缓存合并**是另一个优化点。同一只股票可能被多个用户的多条规则关注，批量查询时应先对所有规则涉及的 `ts_code` 去重，再统一查 Redis 行情缓存，避免对同一只股票重复查询。查询结果在内存中构建 `Map<tsCode, Quote>` 索引，各规则判定时直接从 Map 取值。

**事件触发的异步化**防止判定线程被阻塞。规则判定命中后，不直接在判定线程中执行 AI 简评和消息组装（这两个步骤涉及 LLM 调用，耗时可能超过 10 秒），而是将触发事件写入 Redis 队列，由独立的事件处理消费者异步消费。判定线程只负责"判定 + 入队"，保证每分钟的检查周期不被拖慢。

```
规则判定线程              事件处理消费者
─────────────            ────────────────

判定命中 ──> Redis 队列 ──> 取数（行情/财务/资金数据）
                            │
                            ▼
                        AI 简评生成（调用 LLM）
                            │
                            ▼
                        富媒体消息组装
                            │
                            ▼
                        写入 push_notification + WebSocket 推送
```

**Quartz 线程池大小**根据规则总量动态配置。初期设置线程池大小为 10，足以支撑数千条规则的批量检查。当规则总量超过 1 万条时，将日终检查的 Job 拆分为多个子 Job（按规则类型或 `ts_code` 首字母分组），并行执行。单个 Job 的超时时间设置为 30 秒，超时则记录日志并跳过，避免一个慢查询拖垮整个调度链。

---

## 四、定时任务系统

### 4.1 任务类型与产出

规划书定义了 5 种定时任务类型，每种类型的产出内容和推送渠道有所不同。

| 任务类型 | 代号 | 示例 | 产出内容 | 默认推送渠道 |
|----------|------|------|----------|-------------|
| 晨报 | MORNING_NOTE | "每天 8 点发茅台晨报" | 盘前数据概览 + AI 简评 | 站内 + 邮件 |
| 收盘分析 | CLOSE_ANALYSIS | "每天收盘后分析自选股" | 自选股涨跌 + 异动 + AI 解读 | 站内 |
| 板块周报 | SECTOR_WEEKLY | "每周日发白酒板块报告" | 板块行情 + 龙头股 + 趋势分析 | 站内 + 邮件 |
| 持仓复盘 | PORTFOLIO_REVIEW | "每周五做持仓复盘" | 持仓表现 + 收益归因 + 操作建议 | 站内 |
| 事件跟踪 | EVENT_TRACK | "茅台发财报后通知我" | 财报摘要 + 业绩分析 + 影响评估 | 站内 + 微信 |

事件跟踪任务（EVENT_TRACK）与盯盘引擎的财务事件规则在功能上有重叠，区别在于任务系统的视角是"定期检查并生成完整报告"，而盯盘引擎的视角是"实时触发并推送简评"。事件跟踪任务在财报发布后的首次执行时生成完整分析报告并入库 `analysis_report`，同时推送通知。

### 4.2 Quartz Job 设计

定时任务基于 Quartz 框架实现，使用 JDBC JobStore 保证任务在服务重启后不丢失。每个用户任务对应一个 JobDetail 和一个 CronTrigger。

**JobDetail 构建**以 `task_id` 为唯一标识。Job 类为通用的 `UserTaskJob`，实现了 Quartz 的 `Job` 接口，在 `execute` 方法中根据 JobDataMap 中的 `taskId` 从数据库加载任务详情，然后委托给任务执行引擎处理。

```java
// JobDetail 构建示例
JobDetail jobDetail = JobBuilder.newJob(UserTaskJob.class)
    .withIdentity("task-" + taskId, "user-task-" + userId)
    .usingJobData("taskId", taskId)
    .usingJobData("userId", userId)
    .usingJobData("taskType", taskType)
    .storeDurably()
    .build();

// Trigger 构建（基于 cron 表达式）
CronTrigger trigger = TriggerBuilder.newTrigger()
    .withIdentity("trigger-" + taskId, "user-task-" + userId)
    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpr)
        .withMisfireHandlingInstructionFireAndProceed())
    .build();

// 注册到 Scheduler
scheduler.scheduleJob(jobDetail, trigger);
```

**任务参数序列化**通过 JobDataMap 完成。JobDataMap 中只存储轻量级标识字段（`taskId`、`userId`、`taskType`），任务的完整参数（`params_json` 中的股票代码、板块名称、分析维度等）在 Job 执行时从 `user_task` 表实时加载。这种设计避免了 JobDataMap 存储大段 JSON 导致的序列化性能问题，也保证了任务参数变更后（用户修改了任务配置）无需重新注册 Quartz Job。

**Misfire 策略**采用 `fireAndProceed`（立即执行一次然后恢复正常调度）。如果服务停机导致任务错过了触发时间，重启后立即补执行一次，但不累积多次补执行。例如晨报任务设定为每天 8:00 执行，如果服务在 8:00-8:30 之间停机，9:00 重启后会立即补执行一次晨报，然后第二天 8:00 正常执行。

### 4.3 任务执行引擎流程

任务执行引擎是定时任务系统的核心，从 Quartz 触发到最终推送，经过五个阶段。

```
Quartz 触发 UserTaskJob.execute()
    │
    ▼
┌─────────────────────────────────────────────────────┐
│  阶段 1: 任务加载与预检                               │
│  ├─ 从 user_task 表加载 params_json                  │
│  ├─ 检查 enabled = 1 (任务是否仍启用)                  │
│  ├─ 检查交易日历 (非交易日跳过 MORNING_NOTE 等)        │
│  └─ 更新 last_run_at = 当前时间                       │
└──────────────────┬──────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────┐
│  阶段 2: 取数                                         │
│  ├─ MORNING_NOTE:     取个股昨日行情 + 盘前数据        │
│  ├─ CLOSE_ANALYSIS:   取用户全部自选股当日行情         │
│  ├─ SECTOR_WEEKLY:    取板块成分股 + 板块行情          │
│  ├─ PORTFOLIO_REVIEW: 取用户持仓 + 当周行情            │
│  └─ EVENT_TRACK:      取最新财报/公告数据              │
└──────────────────┬──────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────┐
│  阶段 3: 调用 Graph/AI 生成报告                       │
│  ├─ 根据 task_type 路由到对应 Graph 工作流             │
│  ├─ Graph 节点编排: 取数节点 -> 分析节点 -> 生成节点    │
│  ├─ AI 生成报告标题、摘要、正文(HTML)                  │
│  └─ 报告写入 analysis_report 表 (data_snapshot 记录    │
│     生成时使用的数据快照)                              │
└──────────────────┬──────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────┐
│  阶段 4: 推送                                         │
│  ├─ 构建通知消息(标题 + 摘要 + 富媒体内容JSON)         │
│  ├─ 写入 push_notification 表                         │
│  ├─ 根据 push_channels 路由推送:                      │
│  │   ├─ IN_APP:  WebSocket 实时推送                   │
│  │   ├─ EMAIL:   异步发送邮件                         │
│  │   └─ WECHAT:  微信模板消息推送                     │
│  └─ 关联 report_id 到通知记录                         │
└──────────────────┬──────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────┐
│  阶段 5: 记录日志                                     │
│  ├─ 写入 user_task_log (status=SUCCESS, report_id,  │
│    duration_ms, run_at)                              │
│  ├─ 更新 user_task.next_run_at (Quartz 下次触发时间)  │
│  └─ 异常时 status=FAILED + error_summary             │
└─────────────────────────────────────────────────────┘
```

阶段 3 的 Graph 调用是整个流程中耗时最长的环节，涉及多次 LLM 调用和数据分析。为防止任务执行超时，每个 Graph 工作流设置节点级超时（单个节点 30 秒）和全局超时（整个工作流 3 分钟）。超时后任务标记为部分成功（PARTIAL_SUCCESS），已完成的节点结果仍然入库，未完成的部分在 `error_summary` 中记录。

### 4.4 失败重试与补偿机制

任务执行可能因为多种原因失败：LLM 调用超时、数据库连接异常、Tushare 数据未更新等。失败处理采用**分级重试 + 补偿补发**的策略。

**分级重试**将失败分为可重试和不可重试两类。可重试错误（网络超时、临时服务不可用）自动重试，最多 3 次，重试间隔采用指数退避（10 秒、30 秒、90 秒）。不可重试错误（参数错误、任务已被禁用、数据不存在）直接标记为失败，不重试。

```java
public class UserTaskJob implements Job {
    private static final int MAX_RETRY = 3;
    private static final long[] BACKOFF_MS = {10_000, 30_000, 90_000};

    @Override
    public void execute(JobExecutionContext context) {
        int taskId = context.getMergedJobDataMap().getInt("taskId");
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            try {
                TaskResult result = taskEngine.execute(taskId);
                logSuccess(taskId, result);
                return;
            } catch (RetryableException e) {
                if (attempt < MAX_RETRY) {
                    Thread.sleep(BACKOFF_MS[attempt]);
                    continue;
                }
                logFailure(taskId, e, "重试 3 次后仍失败");
            } catch (NonRetryableException e) {
                logFailure(taskId, e, "不可重试错误");
                return;
            }
        }
    }
}
```

**补偿机制**处理服务停机期间错过的任务。Quartz 的 Misfire 机制只补执行一次，但如果停机时间较长（如超过 1 天），用户可能错过了多天的晨报。补偿机制在服务启动时扫描 `user_task` 表，找出 `last_run_at` 与当前时间差距超过正常周期且 `enabled = 1` 的任务，判断是否需要补执行。补执行只针对当日错过的任务（如今天的晨报），不追溯历史。补执行生成的报告在标题中标注"补发"字样，避免用户困惑。

**事件跟踪任务的特殊补偿**。事件跟踪任务依赖财报/公告数据的发布，如果数据延迟发布（如财报在盘后 20:00 才入库），当日任务可能因数据未就绪而失败。补偿机制在数据入库后触发一次即时检查，如果发现有待执行的事件跟踪任务命中了新数据，自动补执行一次。

### 4.5 任务并发控制

同一用户的多个任务可能同时触发。例如用户设定了"每天 8 点发茅台晨报"和"每天 8 点分析自选股"，两个任务在 8:00 同时被 Quartz 触发。如果不加控制，两个任务同时调用 LLM 可能导致成本突增，也可能因为共享数据源产生竞争。

**用户级串行执行**是并发控制的基本策略。同一用户的任务在 Quartz 中归入同一个分组（`user-task-{userId}`），通过 Quartz 的 `@DisallowConcurrentExecution` 注解和分组级别的信号量保证同一用户同一时刻只有一个任务在执行。如果第二个任务在第一个任务执行期间触发，它会被放入等待队列，等第一个任务完成后立即执行。

```
同一用户任务并发控制

用户 1001 的任务队列:
  8:00  MORNING_NOTE (茅台晨报)     ──执行中──>
  8:00  CLOSE_ANALYSIS (自选股分析)  ──等待──>
  8:00  PORTFOLIO_REVIEW (持仓复盘) ──等待──>

  MORNING_NOTE 完成后 ──> CLOSE_ANALYSIS 开始执行
  CLOSE_ANALYSIS 完成后 ──> PORTFOLIO_REVIEW 开始执行
```

**全局并发上限**通过线程池控制。Quartz 的线程池大小设置为 20，意味着同一时刻最多有 20 个不同用户的任务在并行执行。当在线用户数增长到需要更多并发时，可以动态调整线程池大小，但需要注意 LLM 调用的并发限制。通义千问 DashScope API 有并发请求上限，任务执行引擎在调用 LLM 前获取信号量令牌，超过上限时任务进入等待状态。

**跨用户并行执行**不受限制。不同用户的任务之间没有数据依赖，可以安全地并行执行。Quartz 的线程池天然支持跨用户的并行调度，只要同一用户内部串行即可。

---

## 五、消息通知中心

### 5.1 通知中心设计

消息通知中心是所有个性化事件的统一出口。盯盘引擎触发的监控告警、定时任务生成的分析报告、系统级别的公告通知，都汇聚到通知中心，由它统一管理和推送。

通知中心的交互入口在对话页面右上角的铃铛图标。铃铛上显示红色数字角标，表示未读通知数量。点击铃铛展开通知列表面板，按时间倒序排列，未读通知高亮显示。每条通知包含类型标识、标题、摘要、时间和"查看详情"链接，点击后跳转到对应内容（分析报告、股票详情或任务结果）。通知列表支持按类型筛选（全部/未读/盯盘/任务/系统），支持单条已读、全部已读和批量删除操作。

### 5.2 通知数据模型

通知的数据模型在规划书的 `push_notification` 表基础上补充优先级、有效期和聚合标记字段，以支撑更精细的推送控制。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| user_id | BIGINT | 用户 ID |
| type | VARCHAR(32) | 通知类型：MONITOR（盯盘触发）/ TASK（任务结果）/ SYSTEM（系统通知） |
| priority | VARCHAR(16) | 优先级：HIGH（价格触及/财报发布）/ MEDIUM（任务完成/资金异动）/ LOW（系统公告） |
| title | VARCHAR(255) | 通知标题 |
| summary | TEXT | 通知摘要（一句话概要） |
| content | TEXT | 富媒体内容 JSON（股票卡片/图表数据的完整载荷） |
| report_id | BIGINT | 关联的分析报告 ID（如适用） |
| is_read | TINYINT | 已读状态：0 未读 / 1 已读 |
| is_aggregated | TINYINT | 是否为聚合通知：0 普通通知 / 1 聚合通知 |
| aggregated_count | INT | 聚合通知包含的原始通知条数 |
| valid_until | VARCHAR(32) | 通知有效期，过期后不再推送也不再在列表展示 |
| created_at | VARCHAR(32) | 创建时间 |

**优先级**决定推送渠道的选择。HIGH 优先级通知在用户离线时立即触发邮件推送；MEDIUM 优先级通知在用户离线时仅存入数据库，等用户上线后通过 WebSocket 补发；LOW 优先级通知只在通知列表中展示，不触发任何主动推送。

**有效期**用于自动清理过期通知。例如盘中每分钟检查的价格监控通知，如果用户三天没有查看，则自动标记为已读并从列表中隐藏（不物理删除，保留在数据库中供审计）。有效期的默认值根据通知类型设定：HIGH 为 7 天，MEDIUM 为 3 天，LOW 为 1 天。

### 5.3 WebSocket 实时推送技术实现

站内消息推送通过 WebSocket 实现，是通知中心的核心技术链路。整个推送链路分为连接管理、消息队列和离线补发三个部分。

**连接管理**负责维护用户与 WebSocket Session 的映射关系。用户登录后前端建立 WebSocket 连接，后端将 `userId` 与 `Session` 的映射存入内存中的 `ConcurrentHashMap`。一个用户可能同时打开多个浏览器标签页，因此每个 `userId` 对应一个 `Set<Session>` 集合。连接建立后启动心跳机制，前端每 30 秒发送一次 ping 消息，后端收到后回复 pong。如果后端 60 秒内未收到心跳，判定连接断开，从映射表中移除该 Session。

```
WebSocket 连接管理

┌──────────────────────────────────────────────────────┐
│  WebSocketSessionManager (内存)                       │
│                                                      │
│  ConcurrentHashMap<Long, Set<Session>>              │
│  ├─ userId=1001 -> {Session_A, Session_B}  (两个标签)│
│  ├─ userId=1002 -> {Session_C}             (一个标签)│
│  └─ userId=1003 -> {}                      (离线)   │
│                                                      │
│  心跳检测: 30s ping / 60s 超时断开                    │
│  连接数监控: 单用户最大 5 个连接（防滥用）             │
└──────────────────────────────────────────────────────┘
```

**消息队列**使用 Redis Pub/Sub 作为中间层。通知中心写入 `push_notification` 表后，同时向 Redis 的 `notification:{userId}` 频道发布消息。WebSocket 推送服务订阅所有用户的频道（使用模式订阅 `notification:*`），收到消息后查找对应用户的 Session 集合并推送。这种设计将消息生产（通知中心）和消息消费（WebSocket 推送）解耦，即使 WebSocket 推送服务重启，消息也不会丢失（已持久化在数据库中）。

```
通知推送链路

通知中心 ──> push_notification 表 (持久化)
         │
         └──> Redis Pub/Sub 频道: notification:{userId}
                                        │
                                        ▼
                              WebSocket 推送服务
                              (订阅 notification:*)
                                        │
                              ┌─────────┴─────────┐
                              │                   │
                         在线用户              离线用户
                         推送到 Session        不推送
                         (实时)              (上线时补发)
```

**离线消息补发**在用户重新建立 WebSocket 连接时触发。用户上线后，前端发送一个 `fetchUnread` 消息，后端查询 `push_notification` 表中该用户 `is_read = 0` 且在有效期内的通知，按时间倒序返回（最多 50 条）。前端收到后将这些通知渲染到通知列表中，并在铃铛上显示未读数角标。

如果单用户通知量大（如积累了数百条未读），补发时只返回最近 50 条，更早的通知在用户滚动通知列表时懒加载。这样避免了上线瞬间大量数据传输导致的卡顿。

### 5.4 多渠道推送路由逻辑

通知的推送渠道按优先级分层：站内消息为默认必选渠道，邮件为离线兜底渠道，微信为后期扩展渠道。路由逻辑根据用户在线状态和通知优先级决定走哪条渠道。

```
推送路由决策流程

通知写入 push_notification
    │
    ▼
检查 push_channels 字段
    │
    ├─ IN_APP (必选)
    │   │
    │   ▼
    │   用户是否在线 (WebSocket 连接存在?)
    │   │
    │   ├─ 是 ──> WebSocket 实时推送 ──> 完成
    │   │
    │   └─ 否 ──> 仅入库，等上线补发
    │              │
    │              ▼
    │         检查通知优先级
    │         ├─ HIGH ──> 检查是否启用邮件渠道
    │         │             ├─ 是 ──> 发送邮件 (含报告摘要+站内链接)
    │         │             └─ 否 ──> 跳过
    │         ├─ MEDIUM ──> 跳过 (仅等上线补发)
    │         └─ LOW ──> 跳过
    │
    ├─ EMAIL (可选)
    │   └─ 异步发送邮件 (不依赖在线状态)
    │
    └─ WECHAT (可选，后期接入)
        └─ 微信公众号模板消息推送
```

**站内消息**是所有通知的默认渠道，无需用户额外配置。邮件和微信渠道需要用户在设置页面主动开启，并绑定邮箱或微信账号。如果用户未开启邮件渠道，即使 HIGH 优先级通知也只走站内消息，用户上线后才能看到。

**邮件推送**采用异步发送，避免阻塞通知写入流程。通知写入数据库后，如果需要发邮件，将邮件任务投递到 Redis 队列，由独立的邮件消费者异步处理。邮件内容包含通知标题、摘要和站内链接（而非完整报告），用户点击链接后跳转到平台查看详情。邮件发送结果（成功/失败）记录在日志中，但不影响通知本身的状态。

**微信推送**在 Phase 3 后期接入，通过微信公众号模板消息实现。每条通知类型对应一个微信模板，推送时填充模板变量（股票名称、触发条件、触发时间等）。微信推送有每日额度限制（每个用户每日最多 3 条），超限后降级为站内消息。

### 5.5 通知聚合策略

盯盘引擎在盘中每分钟检查价格规则，如果用户设定了多只股票的价格监控，短时间内可能产生大量触发通知。通知聚合策略避免短时间内大量通知轰炸用户。

**时间窗口聚合**是基础策略。同一用户在 5 分钟内产生的同类通知（相同 `type` 和相同 `priority`）合并为一条聚合通知。聚合通知的标题为"N 条盯盘提醒"，摘要列出触发的前 3 只股票名称和触发条件，点击展开查看全部明细。聚合通知的 `is_aggregated` 字段标记为 1，`aggregated_count` 字段记录包含的原始通知条数。

```
通知聚合示例

9:30:15  茅台跌破 1700  ──> 通知 1
9:30:45  五粮液跌幅超 3% ──> 通知 2
9:31:20  泸州老窖跌破 200 ──> 通知 3
9:32:10  山西汾酒跌幅超 3% ──> 通知 4
         │
         ▼  (5 分钟窗口内同类通知聚合)
9:35:00  推送聚合通知:
         "4 条盯盘提醒"
         摘要: 茅台跌破1700 / 五粮液跌幅超3% / 泸州老窖跌破200 / +1 条
         点击查看全部明细
```

**单规则冷却**防止同一规则在短时间内反复触发。价格监控规则触发后，进入 30 分钟冷却期，冷却期内即使条件仍然满足也不重复触发。涨跌幅监控规则在当日触发后不再重复检查同方向（当日仅触发一次）。冷却期通过 `monitor_rule` 表的 `last_triggered` 字段实现，每次触发时更新该字段为当前时间，检查时比对当前时间与 `last_triggered` 的差值是否超过冷却期。

**突发行情的限流**是最后一道防线。如果整个市场出现剧烈波动（如大盘跌幅超过 3%），大量股票同时触发价格和涨跌幅规则，可能产生通知洪峰。系统设置每用户每分钟最大推送条数上限（默认 10 条），超过上限的通知只入库不推送，在下一个聚合窗口中合并展示。这种限流确保即使在极端行情下，用户的推送通道不会被淹没。

---

## 六、个性化统计模块

### 6.1 模块定位

个性化统计模块是 Phase 3 路线图中提及但未详述的功能，定位为自选股和持仓数据的聚合分析层。它不产生新的数据源，而是基于用户已有的自选股和持仓记录，结合 Tushare 行情和资金数据，生成个性化的统计视图。

四个统计子模块各有侧重。自选股收益统计解决"我的自选股今天表现如何"的问题；资金流监控回答"我的自选股主力资金在流入还是流出"；北向动向关注"外资在买卖我的自选股吗"；财报日历则帮助用户提前知道"近期哪些自选股要发财报"。

### 6.2 自选股收益统计

自选股收益统计聚合用户全部自选股的当日涨跌和累计收益。当日涨跌按用户自选股列表批量查询 `daily_quote` 表的最新行情，计算每只股票的涨跌幅和涨跌额，再汇总为整体表现。累计收益以用户添加自选股的日期为基准，计算从添加日到当前的累计涨跌幅。

统计结果以列表和汇总两种形态返回。列表形态展示每只自选股的当日涨跌幅、累计涨跌幅、添加日期，按当日涨跌幅降序排列。汇总形态展示自选股整体的平均涨跌幅、上涨家数、下跌家数、最大涨幅股票和最大跌幅股票。

```json
// GET /api/watchlist/statistics 响应体
{
  "code": 200,
  "data": {
    "dailySummary": {
      "averageChange": 1.23,
      "upCount": 8,
      "downCount": 3,
      "flatCount": 1,
      "topGainer": { "tsCode": "600519.SH", "name": "贵州茅台", "change": 3.5 },
      "topLoser": { "tsCode": "002594.SZ", "name": "比亚迪", "change": -1.2 }
    },
    "stocks": [
      {
        "tsCode": "600519.SH",
        "stockName": "贵州茅台",
        "currentPrice": 1705.00,
        "dailyChange": 3.50,
        "cumulativeChange": 8.20,
        "addedDate": "2026-07-01"
      }
    ]
  }
}
```

数据源是 `daily_quote` 表和 `user_watchlist` 表的 `created_at` 字段。计算累计收益时，以 `user_watchlist.created_at` 对应交易日的收盘价作为基准价，当前收盘价减去基准价再除以基准价即为累计涨跌幅。

### 6.3 资金流监控

资金流监控汇总用户自选股的主力资金流向。系统从 `moneyflow` 表查询自选股列表中所有股票的主力净流入金额，按股票和行业两个维度聚合。

按股票维度，列出每只自选股当日的主力净流入金额和近 5 日累计净流入，标注"主力净流入"或"主力净流出"。按行业维度，将自选股按申万一级行业分组，汇总各行业的主力净流入总额，帮助用户识别资金在行业间的流向趋势。

```json
// GET /api/watchlist/moneyflow 响应体
{
  "code": 200,
  "data": {
    "totalNetInflow": 350000000,
    "byStock": [
      { "tsCode": "600519.SH", "name": "贵州茅台", "netInflow": 250000000, "trend": "INFLOW" },
      { "tsCode": "000858.SZ", "name": "五粮液", "netInflow": 100000000, "trend": "INFLOW" }
    ],
    "byIndustry": [
      { "industry": "食品饮料", "netInflow": 350000000, "stockCount": 2 },
      { "industry": "银行", "netInflow": -50000000, "stockCount": 1 }
    ]
  }
}
```

数据源是 `moneyflow` 表（Tushare `moneyflow` 接口），主力资金定义为单笔成交额大于 100 万元的买卖差额。检查频率为盘中每小时更新一次，盘后做最终汇总。

### 6.4 北向动向

北向动向汇总用户自选股的沪深港通持股变动。系统从 `hk_hold` 表查询自选股列表中所有股票的北向资金持股数量和持股比例，计算当日变动和近 5 日累计变动。

统计结果按股票维度列出每只自选股的北向持股比例、当日持股变动（股数和比例）、近 5 日累计变动。同时标注"北向增持"或"北向减持"，帮助用户跟随外资动向。

```json
// GET /api/watchlist/hk-hold 响应体
{
  "code": 200,
  "data": {
    "stocks": [
      {
        "tsCode": "600519.SH",
        "stockName": "贵州茅台",
        "holdRatio": 8.25,
        "dailyChange": 0.12,
        "weeklyChange": 0.35,
        "trend": "INCREASE"
      }
    ],
    "summary": {
      "totalIncrease": 6,
      "totalDecrease": 2,
      "netChangeRatio": 0.08
    }
  }
}
```

数据源是 `hk_hold` 表（Tushare `hk_hold` 接口），记录沪深港通持股的每日明细。当日变动通过与前一交易日的持股数据对比计算，近 5 日累计变动通过 5 个交易日前的数据对比计算。

### 6.5 财报日历

财报日历展示用户自选股近期（未来 30 天）的财报披露时间表。系统根据自选股列表查询每只股票的财报披露日期，按时间排列形成日历视图。

财报披露日期的来源是 Tushare 的财务接口返回的 `end_date`（报告期）和推算的法定披露期限。A 股财报披露有时间窗口约束：年报在次年 4 月 30 日前披露，一季报在 4 月 30 日前披露，半年报在 8 月 31 日前披露，三季报在 10 月 31 日前披露。系统根据当前日期和报告期推算出预计披露窗口，在日历上标注。

```json
// GET /api/watchlist/finance-calendar 响应体
{
  "code": 200,
  "data": {
    "upcoming": [
      {
        "tsCode": "600519.SH",
        "stockName": "贵州茅台",
        "reportType": "半年报",
        "reportPeriod": "2026-06-30",
        "expectedWindow": "2026-08-15 ~ 2026-08-31",
        "daysUntilDeadline": 25
      },
      {
        "tsCode": "000858.SZ",
        "stockName": "五粮液",
        "reportType": "半年报",
        "reportPeriod": "2026-06-30",
        "expectedWindow": "2026-08-20 ~ 2026-08-31",
        "daysUntilDeadline": 25
      }
    ]
  }
}
```

数据源是 `income` / `balancesheet` / `cashflow` / `fina_indicator` 表中的 `ann_date` 字段（已披露财报的实际公告日期）和 `end_date` 字段（报告期）。对于尚未披露的财报，根据报告期推算法定披露窗口。系统每日检查 `ann_date` 是否有新记录，如果发现某只自选股的财报已实际披露，自动生成一条通知推送给用户，并触发事件跟踪任务生成分析报告。

---

## 七、数据模型与索引设计

### 7.1 表结构总览

个性化功能涉及 6 张 C 端用户业务表，加上 1 张报告表和 1 张审计表，共 8 张表。以下是各表的职责和关联关系。

```
ER 关系图

user (用户主表，来自认证模块)
 │
 ├──< user_watchlist (自选股)
 │       字段: user_id, ts_code, group_name, target_price, note, sort_order
 │
 ├──< user_portfolio (模拟持仓)
 │       字段: user_id, ts_code, buy_price, quantity, buy_date, note
 │
 ├──< monitor_rule (盯盘规则)
 │       字段: user_id, ts_code, rule_type, rule_params, push_channels,
 │              enabled, last_triggered
 │
 ├──< user_task (定时任务)
 │       │
 │       └──< user_task_log (任务执行日志)
 │               字段: task_id, run_at, status, report_id, error_summary, duration_ms
 │
 ├──< analysis_report (分析报告)
 │       字段: user_id, conversation_id, report_type, ts_code, industry,
 │              title, summary, content, data_snapshot, is_favorited
 │
 └──< push_notification (推送通知)
         字段: user_id, type, priority, title, summary, content,
                report_id, is_read, is_aggregated, aggregated_count, valid_until
```

### 7.2 关键 ER 关系说明

**user_watchlist 与 monitor_rule**是松耦合关系。盯盘规则通过 `ts_code` 关联到自选股，但不通过外键约束。用户可以直接对任意股票创建盯盘规则，不必先加入自选股。但从用户体验角度，对话中创建盯盘规则时，系统会提示"是否同时加入自选股"，便于后续统一管理。

**user_task 与 user_task_log**是父子关系。一条 `user_task` 记录对应多条 `user_task_log` 记录，每次任务执行生成一条日志。`user_task_log.report_id` 关联到 `analysis_report.id`，表示该次执行产出的报告。如果任务执行失败（`status = FAILED`），`report_id` 为空，`error_summary` 记录失败原因。

**push_notification 与 analysis_report**通过 `report_id` 关联。盯盘触发类通知通常不关联报告（`report_id` 为空），只推送简评；定时任务类通知关联到任务产出的报告，用户点击"查看详情"跳转到报告详情页。

**user_portfolio 与 stock_basic**通过 `ts_code` 关联。持仓的行业归属从 `stock_basic.industry` 字段获取，不冗余存储在持仓表中，避免行业分类变更时的数据不一致。

### 7.3 索引设计建议

索引设计围绕高频查询场景展开。以下是在规划书已有索引基础上的补充建议。

```sql
-- user_watchlist: 补充联合索引，支持按用户+分组查询
-- 已有: idx_watchlist_user(user_id), idx_watchlist_code(ts_code)
-- 补充: 按用户+分组+排序查询（自选股面板高频场景）
CREATE INDEX idx_watchlist_user_group ON user_watchlist(user_id, group_name, sort_order);

-- 补充: 按用户+股票查询（判断某股票是否已在自选股中）
CREATE UNIQUE INDEX uk_watchlist_user_code_group ON user_watchlist(user_id, ts_code, group_name);


-- user_portfolio: 补充联合索引，支持按用户+股票查询
-- 已有: idx_portfolio_user(user_id)
-- 补充: 按用户+股票查询（持仓去重检查）
CREATE INDEX idx_portfolio_user_code ON user_portfolio(user_id, ts_code);


-- monitor_rule: 补充联合索引，支持按规则类型批量加载
-- 已有: idx_monitor_user(user_id), idx_monitor_code(ts_code)
-- 补充: 按规则类型+启用状态批量加载（Quartz Job 高频场景）
CREATE INDEX idx_monitor_type_enabled ON monitor_rule(rule_type, enabled);

-- 补充: 按用户+股票+启用状态查询（冲突检测场景）
CREATE INDEX idx_monitor_user_code_enabled ON monitor_rule(user_id, ts_code, enabled);


-- user_task: 补充联合索引，支持按用户+启用状态查询
-- 已有: idx_user_task_user(user_id)
-- 补充: 按用户+启用状态查询（任务管理页面）
CREATE INDEX idx_user_task_user_enabled ON user_task(user_id, enabled);

-- 补充: 按下次执行时间查询（补偿机制扫描场景）
CREATE INDEX idx_user_task_next_run ON user_task(next_run_at);


-- user_task_log: 补充联合索引，支持按任务+时间查询
-- 已有: idx_task_log_task(task_id)
-- 补充: 按任务+时间倒序查询（执行历史页面）
CREATE INDEX idx_task_log_task_time ON user_task_log(task_id, run_at);

-- 补充: 按状态查询失败任务（重试扫描场景）
CREATE INDEX idx_task_log_status ON user_task_log(status, run_at);


-- push_notification: 补充联合索引，支持按用户+时间倒序查询
-- 已有: idx_notif_user(user_id, is_read), idx_notif_time(created_at)
-- 补充: 按用户+类型+时间倒序查询（通知列表筛选场景）
CREATE INDEX idx_notif_user_type_time ON push_notification(user_id, type, created_at);

-- 补充: 按有效期查询（过期通知清理场景）
CREATE INDEX idx_notif_valid_until ON push_notification(valid_until);


-- analysis_report: 补充联合索引，支持按用户+类型+时间查询
-- 已有: idx_report_user(user_id), idx_report_type(report_type),
--       idx_report_code(ts_code), idx_report_fav(user_id, is_favorited)
-- 补充: 按用户+时间倒序查询（报告中心列表页面）
CREATE INDEX idx_report_user_time ON analysis_report(user_id, created_at);

-- 补充: 按用户+股票+时间倒序查询（某股票历史报告）
CREATE INDEX idx_report_user_code_time ON analysis_report(user_id, ts_code, created_at);
```

索引设计遵循三个原则。第一是**最左前缀匹配**，联合索引的字段顺序按照查询条件的出现频率从高到低排列。第二是**避免冗余索引**，如果已有联合索引 `(user_id, is_read)` 可以覆盖 `user_id` 单字段查询，就不再单独建 `user_id` 索引。第三是**控制索引数量**，每张表的索引总数控制在 5-7 个以内，避免写入性能下降。

所有时间字段使用 `VARCHAR(32)` 而非 `DATETIME` 类型，与 stock-pulse 项目的约定保持一致。时间字符串格式为 `yyyy-MM-dd HH:mm:ss`，在应用层统一格式化。这种设计避免了 MySQL 时区配置的复杂性，也便于在 JSON 序列化时直接使用。

---

## 八、安全与合规要点

### 8.1 越权防护

所有个性化接口在 Service 层强制注入当前登录用户的 `user_id`，Controller 层不接收 `userId` 参数。MyBatis-Plus 的查询 Wrapper 通过 `currentUserHolder.getUserId()` 获取当前用户 ID，确保查询结果只包含当前用户的数据。

对于 `id` 路径参数（如 `DELETE /api/watchlist/{id}`），Service 层在执行删除前先校验该记录的 `user_id` 是否与当前用户一致。如果不一致，返回 404 而非 403，避免泄露其他用户数据的存在性。

### 8.2 推送内容合规

所有推送内容在发送前经过合规过滤。AI 生成的简评和报告摘要不包含"建议买入""建议卖出"等直接投资建议措辞，统一替换为中性表述（如"值得关注""需要警惕"）。合规过滤通过 Spring AI 的 Advisor 机制实现，在 LLM 输出后、写入通知前执行正则匹配和替换。

通知标题和摘要不包含具体涨跌幅数字的绝对值表述（如"茅台大涨 8%"），改为相对表述（如"茅台涨幅较大"），避免被误读为投资建议。完整数据在用户点击查看详情后展示。

### 8.3 推送频率控制

单用户每日推送频率设上限，防止盯盘规则过多导致通知轰炸。默认上限为每日 50 条通知（含所有类型），超过上限后通知只入库不推送，次日通过聚合通知提醒用户。用户可在设置页面调整此上限，但最低不低于 10 条、最高不超过 200 条。

邮件推送的频率限制更严格，每日最多 5 封，避免被邮件服务商标记为垃圾邮件。微信推送每日最多 3 条，受限于微信公众号接口的发送频率约束。

---

## 九、性能与容量预估

### 9.1 关键场景性能目标

| 场景 | 目标响应时间 | 说明 |
|------|-------------|------|
| 自选股列表查询 | < 200ms | 含实时行情批量查询，Redis 缓存命中 |
| 持仓概览计算 | < 300ms | 含盈亏计算和行业聚合 |
| 通知列表查询 | < 100ms | 分页查询，走 (user_id, created_at) 索引 |
| WebSocket 消息推送延迟 | < 500ms | 从通知写入到前端收到 |
| 盯盘规则批量检查（1000 条） | < 5 秒 | 批量查询行情 + 逐条判定 |
| 定时任务执行（晨报） | < 30 秒 | 含取数 + Graph 分析 + 报告生成 |

### 9.2 容量预估

以 Phase 3 目标用户量 1000 人为基准，估算各表的存储和访问压力。

| 表 | 预估行数 | 日增量 | 主要查询模式 |
|------|---------|--------|-------------|
| user_watchlist | 1 万（人均 10 只） | 200 | 按用户+分组查询 |
| user_portfolio | 3000（人均 3 只） | 100 | 按用户查询 |
| monitor_rule | 5000（人均 5 条） | 150 | 按规则类型批量加载 |
| user_task | 2000（人均 2 个） | 50 | 按用户+启用状态查询 |
| user_task_log | 日增 2000（每日执行约 2000 次） | 2000 | 按任务+时间查询，3 个月清理 |
| push_notification | 日增 5000（含盯盘触发） | 5000 | 按用户+时间查询，7 天清理 |
| analysis_report | 日增 2000（含任务产出） | 2000 | 按用户+类型+时间查询 |

`user_task_log` 和 `push_notification` 是增长最快的两张表，需要定期清理。`user_task_log` 保留 3 个月历史数据，更早的归档到冷存储。`push_notification` 保留 30 天，过期的通知标记为已读并从列表隐藏（不物理删除，保留审计痕迹）。清理任务由 Quartz 的系统级 Job 每日凌晨执行，批量删除过期数据时按 `id` 范围分批操作，避免长事务锁表。

`analysis_report` 的 `content` 字段为 `LONGTEXT`，单份报告可能达到数十 KB。建议在 `content` 超过 100 KB 时将完整内容写入对象存储（如 MinIO），数据库中只存储摘要和对象存储路径，减轻数据库存储压力。这一优化在 Phase 3 后期根据实际报告大小决定是否实施。
