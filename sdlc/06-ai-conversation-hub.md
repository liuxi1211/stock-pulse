# AI 对话中枢设计

> 本文档覆盖 C 端用户应用的 AI 对话中枢后端设计，包括意图路由、ChatMemory 持久化、Function Calling 工具体系、Spring AI Alibaba Graph 多智能体编排、RAG 知识库、AI 幻觉防护体系与模型成本控制。文档编号 06，对应规划书第八章并做深度扩展，是 03 富交互 Chat 前端设计与 05 专业报告中心设计在后端侧的承接文档。

---

## 一、设计总览

### 1.1 对话中枢的定位

AI 对话中枢是用户消息的**统一入口和处理中枢**。前端通过 SSE 推送上来的每一条自然语言消息，都先进入对话中枢，再由中枢决定走取数、走分析、走报告生成还是走操作指令。中枢向上对接通义千问模型，向下通过 Function Calling 调用数据查询服务、研究方法引擎和用户中心，向左通过 ChatMemory 维护多轮上下文，向右通过 RAG 检索注入领域知识。

对话中枢不是一个大 ChatClient，而是由五个职责明确的子系统组合而成。这五个子系统分别承担"听懂、记住、调用、编排、防护"五个动作，对应规划书架构图中 AI 对话中枢内部标注的五个组件。

### 1.2 核心设计原则：数据与 AI 分离

**数据与 AI 分离**是对话中枢的底层约束，贯穿所有子系统的设计。数值计算全部走确定性代码：PE 分位、技术指标、资金净流入、DCF 折现值都由 Java 后端或 Python 计算服务算好后以 JSON 传入，AI 只负责解读和表达。这条原则直接决定了 Function Calling 工具的返回结构--每个工具返回的是成品数据，而非让 AI 自行推算的原始字段。

这条原则的代价是工具数量较多、编排链路较长，但收益是**幻觉风险被压缩到文本层**。即便模型把"PE 35.2 处于近 5 年 78% 分位"这句话说得不够顺，也不会出现"PE 352 分位 780%"这种数值层面的荒谬错误，因为数字本身来自确定性代码。

### 1.3 与 Spring AI Alibaba 的关系

对话中枢基于 Spring AI Alibaba 的五大组件构建，每个组件对应一个子系统，职责边界清晰：

| Spring AI Alibaba 组件 | 对话中枢子系统 | 承载的职责 |
|---|---|---|
| ChatClient | 模型调用层 | 封装通义千问调用，统一 System Prompt、模型分级、流式输出 |
| ChatMemory | 上下文管理层 | 按 conversationId 维护对话历史，超窗口摘要，用户偏好持久化 |
| Function Calling | 工具调用层 | 16 个工具的注册、发现、调用、错误处理与审计 |
| Graph | 多智能体编排层 | 18 种研究方法映射为节点，Workflow 串联/并行/分支/循环编排 |
| RAG | 知识检索层 | 三个向量索引的构建、检索、重排序与 prompt 注入 |

五大组件之间的关系是**调用而非包含**。意图路由决定一条消息进入哪个组件：轻量查询直接走 Function Calling 不经过 Graph；单方法分析走 Graph 单节点；多步骤研究走 Graph 多节点 Workflow；闲聊兜底只经过 ChatClient。RAG 和 ChatMemory 作为横切能力，被各条处理链按需调用。

### 1.4 一次对话的完整流转

以"全面分析茅台"为例，展示对话中枢内部五个子系统的协作顺序：

```
用户消息「全面分析茅台」
  │
  ▼
① 意图路由 ── 识别为「多步骤研究」
  │
  ▼
② ChatMemory ── 加载 conversationId 的近 20 条消息 + 用户偏好
  │
  ▼
③ Graph 编排 ── 启动「个股深度研究」Workflow
  │   ├─ 节点1 tear-sheet      → Function Calling queryDailyBasic
  │   ├─ 节点2 technical        → Function Calling calculateIndicator
  │   ├─ 节点3 valuation       → Function Calling queryDailyBasic（历史分位）
  │   ├─ 节点4 financial       → Function Calling queryFinancial
  │   ├─ 节点5 moneyflow       → Function Calling queryMoneyflow
  │   └─ 节点6 report-assemble → RAG 检索方法论 + ChatClient 生成报告
  │
  ▼
④ 幻觉防护 ── 报告节点数据校验：AI 文本中引用的数字与工具返回值比对
  │
  ▼
⑤ 审计落库 ── ai_analysis_audit 记录意图、工具调用链、Token 消耗、耗时
  │
  ▼
返回报告预览容器（富媒体流，经 SSE 推送前端）
```

后续章节按意图路由、ChatMemory、Function Calling、Graph、RAG、幻觉防护、模型成本的顺序，逐一展开设计。

---

## 二、意图路由设计

### 2.1 意图路由表

用户消息进入对话中枢后，第一步是判断处理方式。规划书第八章定义了六类意图，路由表如下：

| 意图 | 处理方式 | 输出形态 | 典型示例 |
|---|---|---|---|
| 轻量查询 | Function Calling 取数 | 股票卡片 + 一句话 | 茅台今天的 PE |
| 单方法分析 | 路由到对应 Graph 节点 | 图表卡片 + 结构化文本 | 茅台技术面怎么样 |
| 多步骤研究 | 编排多节点 Workflow | 报告预览容器 | 全面分析茅台 |
| 操作指令 | Function Calling 触发操作 | 任务确认容器 | 盯住茅台跌破 1700 通知我 |
| 选股筛选 | Function Calling 组合查询 | 股票列表卡片 | PE<20 且 ROE>15% 的股票 |
| 闲聊/兜底 | 直接 ChatClient | 纯文本 | 你好 |

### 2.2 意图识别的混合方案

意图识别采用**基于规则的关键词匹配**与**基于 LLM 的意图分类**两层混合方案。规则层在前，LLM 层在后，绝大多数消息在规则层就能判定，只有规则层无法覆盖或置信度不足时才回落到 LLM 层。这样设计的原因是规则层延迟低（<5ms）且零 Token 成本，而 LLM 层虽然准确但每次调用都消耗 Token 和时间。

规则层维护一张关键词到意图的映射表，按优先级从高到低匹配。匹配命中且关键词特异性足够时直接返回意图，不再调用 LLM。例如"跌破/突破/通知我/盯住"强指向操作指令，"全面分析/深度分析/研报"强指向多步骤研究，"筛选/大于/小于/且"强指向选股筛选。

当规则层命中多个意图、或一个都没命中、或命中但置信度不足时，进入 LLM 层。LLM 层用一个轻量意图分类 Prompt，把用户消息、最近 3 条对话历史和六个意图的定义一起送给 qwen-turbo，要求模型输出一个固定结构的 JSON：

```json
{
  "intent": "single_method_analysis",
  "confidence": 0.85,
  "method": "technical_analysis",
  "ambiguous_with": null,
  "need_clarification": false
}
```

LLM 层的输出除了意图标签，还携带置信度和歧义标记。当 `confidence` 低于 0.6 或 `ambiguous_with` 非空时，触发歧义澄清流程（见 2.4 节）。意图分类的 Prompt 缓存在 Caffeine 中按版本号管理，避免每次请求重复拼装长 Prompt。

### 2.3 意图到处理链的映射

每种意图对应一条处理链，链的长短取决于任务复杂度。意图与处理链的映射关系决定了消息进入哪个子系统：

| 意图 | 是否经过 Graph | 处理链 | 模型分级 |
|---|---|---|---|
| 轻量查询 | 否 | Function Calling → ChatClient 一句话解读 | qwen-turbo |
| 单方法分析 | 是（单节点） | Graph 单节点 → 节点内 Function Calling + ChatClient | qwen-plus |
| 多步骤研究 | 是（多节点） | Graph Workflow 编排多节点 → 报告组装节点 | qwen-max |
| 操作指令 | 否 | 规则解析 → Function Calling 写库 → 确认容器 | qwen-turbo |
| 选股筛选 | 否 | Function Calling 组合查询 → ChatClient 简评 | qwen-turbo |
| 闲聊/兜底 | 否 | ChatClient 直出 | qwen-turbo |

一个关键设计是**操作指令不经过 Graph**。用户说"盯住茅台跌破 1700 通知我"时，意图路由识别出操作指令后，先用 qwen-turbo 把自然语言解析成结构化的 `MonitorRuleDTO`（规则类型、阈值、股票代码、推送渠道），再调用 `createMonitorRule` 工具写库，最后返回任务确认容器。这条链路全程不需要 Graph 编排，因为操作本身就是一次性的结构化写入。

### 2.4 意图歧义的澄清对话

当用户意图不明确时，对话中枢不强行猜测，而是发起**澄清对话**。澄清的触发条件有三类：LLM 层置信度低于 0.6、命中多个等价意图、或用户消息缺少必要参数（如只说"分析一下"没说分析哪只股票）。

澄清对话通过**交互表单容器**呈现给用户，而非纯文本追问。交互表单容器在 03 前端设计文档中定义，这里承载的是结构化的选项按钮。例如用户说"分析一下银行股"，LLM 识别出歧义（是分析银行板块行情，还是筛选银行股，还是分析某只银行股），返回：

```
┌─────────────────────────────────────────┐
│  你说的「分析一下银行股」，我需要确认方向：    │
│                                         │
│  [板块行情综述]  [筛选银行股]  [分析个股]   │
│                                         │
│  或直接输入股票代码，如「分析招商银行」        │
└─────────────────────────────────────────┘
```

澄清对话的状态保存在 ChatMemory 中。当用户点击某个选项后，系统把用户的澄清选择作为新消息送入意图路由，此时上下文已包含上一次的歧义记录，路由层会直接采用用户选择的意图，不再重复澄清。为避免无限澄清，每条对话链路最多发起两次澄清，超过两次后按置信度最高的意图兜底执行并在回复中标注"已按默认理解执行"。

### 2.5 轻量查询快速通道

轻量查询是最高频的意图（占比预计超过 50%），必须走**快速通道**绕开 Graph。快速通道的设计目标是把"茅台今天的 PE"这类请求的端到端延迟压到 2 秒以内，其中模型调用只发生一次且用最便宜的 qwen-turbo。

快速通道的处理链是：规则层命中轻量查询 → 直接调用对应 Function Calling 工具（如 `queryDailyBasic`）→ 拿到结构化数据 → 用一个极简 Prompt 让 qwen-turbo 生成一句话解读 → 组装成股票卡片 + 一句话返回。全程不构建 Graph、不检索 RAG、不写长期记忆，只写一条审计日志。

快速通道的 Prompt 是预置模板，按工具类型缓存。例如 `queryDailyBasic` 对应的解读模板固定为"当前 PE {pe}，处于近 {years} 年 {percentile}% 分位，{verdict}"，模型只需填空而非自由发挥，进一步压缩 Token 和延迟。

---

## 三、ChatMemory 设计

### 3.1 ChatMemory 总体设计

规划书第八章对 ChatMemory 的定义是四条：按 conversationId 维护对话上下文、窗口 20 条消息、超窗口旧消息摘要存入长期记忆、用户偏好持久化跨会话生效。这四条对应三个层次：**短期窗口**管当前对话的连贯性，**长期摘要**管跨窗口的记忆压缩，**用户偏好**管跨会话的个性化。

Spring AI 提供了 `ChatMemory` 接口和 `MessageWindowChatMemory` 开箱实现，但开箱实现基于内存，重启即丢。对话中枢需要把 ChatMemory 持久化到 MySQL，实现跨重启、跨会话的记忆保持。

### 3.2 ChatMemory 持久化方案

持久化的核心是自定义一个 `MysqlChatMemory` 实现 `ChatMemory` 接口，把消息读写映射到 `chat_message` 表。`chat_message` 表在规划书第十章已定义，字段包括 id、conversation_id、role、content、tool_calls_json、tool_result_json、created_at。

`ChatMemory` 接口的核心方法与表操作的映射关系如下：

```java
public class MysqlChatMemory implements ChatMemory {

    // 读取：按 conversation_id 正序取最近 N 条
    // N = 窗口大小（20），role 区分 user/assistant/tool
    @Override
    public List<Message> load(String conversationId) {
        return chatMessageMapper.selectRecent(conversationId, WINDOW_SIZE)
                .stream()
                .map(this::toMessage)  // DO -> Spring AI Message
                .toList();
    }

    // 写入：追加一条消息，同时触发窗口管理（见 3.3）
    @Override
    public void save(String conversationId, List<Message> messages) {
        // 增量写入新增的消息，并执行窗口溢出检查
    }
}
```

消息的 `role` 字段对应 Spring AI 的 `MessageType`（USER / ASSISTANT / SYSTEM / TOOL）。工具调用的 `tool_calls` 和 `tool_result` 单独存两列 JSON，不混入 content，这样前端渲染对话历史时可以区分"纯文本回复"和"工具调用过程"。

### 3.3 窗口管理：滑动窗口 + 旧消息摘要

窗口管理解决的是"对话越长，上下文越塞越多，Token 爆炸"的问题。策略是**滑动窗口 + 溢出摘要**：保留最近 20 条消息原文，超出窗口的旧消息不直接丢弃，而是定期压缩成摘要存入长期记忆。

摘要的触发时机是窗口溢出时，而非每条消息都压缩。具体算法如下：

```
当 conversationId 的消息数超过 WINDOW_SIZE(20) 时：
  1. 取出将被挤出窗口的旧消息批次（如第 21~25 条）
  2. 调用 qwen-turbo 生成摘要：
     "用户在此前询问了茅台的 PE 分位，AI 回复当前 PE 35.2 处于
      78% 分位偏贵，用户随后追问了资金流向，AI 回复主力净流出..."
  3. 摘要追加到 conversation 的 long_term_summary 字段
  4. 旧消息原文保留在 chat_message 表（不物理删除），
     但从 load() 的返回结果中移除，不再注入 prompt
  5. 下次 load() 返回：[long_term_summary 作为 system 消息] + [最近 20 条]
```

摘要本身也会膨胀，因此对 `long_term_summary` 做二次压缩：当摘要长度超过 500 字时，对摘要再做一次摘要，保留最近的要点。这样长期记忆的长度被控制在 500 字以内，不会随对话无限增长。

窗口大小 20 条是可配置常量（`chat.memory.window-size`），不同意图可微调：轻量查询场景其实不需要 20 条上下文，快速通道可降到 5 条进一步省 Token；多步骤研究场景可维持 20 条保证连贯性。

### 3.4 用户偏好持久化

用户偏好是对话中隐含的个性化信息，如关注板块、估值偏好、风险偏好等。这些信息跨会话生效，不能只存在单个 conversation 的窗口里，需要单独的持久化结构。

偏好提取采用**后台异步**方式，不阻塞对话主链路。每轮对话结束后，把当轮消息异步送入一个偏好抽取 Prompt，让 qwen-turbo 判断是否包含可提取的偏好信号。抽取出的偏好写入 `user_preference` 表：

```sql
CREATE TABLE user_preference (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    pref_key      VARCHAR(64) NOT NULL,   -- 如 watched_sectors / valuation_bias / risk_appetite
    pref_value    VARCHAR(512) NOT NULL,  -- 如 "白酒,半导体" / "偏低估值" / "稳健"
    confidence    DECIMAL(3,2) DEFAULT 0.5,
    source_conv   BIGINT,                 -- 来源会话 ID
    updated_at    VARCHAR(32),
    UNIQUE KEY uk_user_pref (user_id, pref_key),
    INDEX idx_pref_user (user_id)
);
```

偏好注入对话的方式是在 System Prompt 中追加一段用户画像。当用户开启新会话时，`load()` 除了加载窗口消息，还会查出该用户的偏好并拼成 System 消息："该用户偏好低估值股票，近期关注白酒和半导体板块，风险偏好稳健"。这样即使用户没在新会话里重复声明偏好，AI 也能延续个性化。

偏好的更新策略是**最新覆盖**而非累积。同一 `pref_key` 的偏好以最近一次高置信度抽取为准，避免早期的一次性表述长期污染画像。当用户在对话中明确修正偏好（如"我现在不看白酒了，改看新能源"），抽取 Prompt 会识别这种否定语义并覆盖旧值。

### 3.5 会话管理生命周期

会话列表在左侧边栏展示，支持创建、切换、重命名、删除四个动作。会话的元数据存在 `chat_conversation` 表，消息存在 `chat_message` 表，两者通过 `conversation_id` 关联。

**创建**会话时生成一条 `chat_conversation` 记录，title 默认为"新对话"，待用户发送第一条消息后由 qwen-turbo 生成一个不超过 12 字的标题回填（如"茅台 PE 查询"）。**切换**会话时前端传入新的 conversationId，后端从 `chat_message` 重新 load 窗口消息和长期摘要，重建上下文。**重命名**直接更新 title 字段。**删除**采用软删除策略：标记 `chat_conversation.deleted = 1`，消息和摘要保留 30 天后物理清理，30 天内支持恢复，避免误删导致用户资产丢失。

会话数量不做硬性上限，但 `chat_conversation` 列表查询按 `updated_at` 倒序分页，前端默认只加载最近 50 条，更多通过滚动加载。超过 90 天未活动的会话归档到冷存储表，不参与默认列表渲染，用户搜索时再唤回。

---

## 四、Function Calling 工具体系

### 4.1 工具清单与富媒体容器映射

规划书第八章给出了 16 个 Function Calling 工具，每个工具对应一种数据查询或操作能力。工具的返回不只是数据，还携带**富媒体容器类型**标记，前端据此选择渲染组件。容器类型与 03 前端设计文档定义的六类容器一一对应：

| 工具 | 功能 | 输出容器类型 | 数据来源 |
|---|---|---|---|
| `queryStockBasic` | 查股票基本信息 | 轻量卡片 | stock_basic 表 |
| `queryDailyQuote` | 查日线行情 | 股票卡片 + K 线图 | daily_quote 表 |
| `queryDailyBasic` | 查每日估值指标 | 估值卡片 + 分位图 | daily_basic 表 |
| `queryFinancial` | 查财务三表 + 指标 | 财务表格卡片 | income/balancesheet/cashflow/fina_indicator 表 |
| `queryMoneyflow` | 查资金流向 | 资金流向图卡片 | moneyflow 表 |
| `queryTopList` | 查龙虎榜 | 龙虎榜表格卡片 | top_list 表 |
| `queryMarginDetail` | 查融资融券 | 融资融券趋势图 | margin_detail 表 |
| `queryHkHold` | 查沪深港通持股 | 北向资金趋势图 | hk_hold 表 |
| `querySwIndustry` | 查申万行业成分 | 行业股票列表 | index_member / sw_daily 表 |
| `calculateIndicator` | 计算技术指标 | 技术指标图表 | Python 计算服务 :8085 |
| `generateReport` | 生成分析报告 | 报告预览容器 | analysis_report 表 |
| `createUserTask` | 创建定时任务 | 任务确认容器 | user_task 表 |
| `createMonitorRule` | 创建盯盘规则 | 任务确认容器 | monitor_rule 表 |
| `addWatchlist` | 添加自选股 | 操作结果提示 | user_watchlist 表 |
| `queryWatchlist` | 查询自选股 | 自选股列表卡片 | user_watchlist 表 |
| `queryPortfolio` | 查询持仓 | 持仓概览卡片 | user_portfolio 表 |

### 4.2 工具输入参数 Schema

每个工具通过 Spring AI 的 `@Tool` 注解或 `FunctionCallback` 注册时声明参数 schema，模型据此生成调用参数。以下列出四个代表性工具的完整 schema，其余工具遵循同一结构。

**queryDailyBasic**（查每日估值指标）：

```json
{
  "name": "queryDailyBasic",
  "description": "查询指定股票在指定日期的估值指标（PE/PB/股息率等）及历史分位",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ts_code": { "type": "string", "description": "股票代码，如 600519.SH", "required": true },
      "trade_date": { "type": "string", "description": "交易日期 YYYYMMDD，默认最新交易日", "required": false },
      "percentile_years": { "type": "integer", "description": "分位计算的回溯年数，默认 5", "required": false }
    },
    "required": ["ts_code"]
  }
}
```

**queryFinancial**（查财务三表 + 指标）：

```json
{
  "name": "queryFinancial",
  "description": "查询股票的利润表、资产负债表、现金流量表及关键财务指标",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ts_code": { "type": "string", "required": true },
      "report_type": { "type": "string", "enum": ["income","balancesheet","cashflow","fina_indicator","all"], "default": "all" },
      "period": { "type": "string", "description": "报告期 YYYYMMDD，默认最新", "required": false },
      "limit": { "type": "integer", "description": "返回最近几期，默认 4", "required": false }
    },
    "required": ["ts_code"]
  }
}
```

**calculateIndicator**（计算技术指标）：

```json
{
  "name": "calculateIndicator",
  "description": "调用计算服务计算技术指标（MA/MACD/KDJ/BOLL/RSI/ATR）",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ts_code": { "type": "string", "required": true },
      "indicators": { "type": "array", "items": { "type": "string", "enum": ["MA","MACD","KDJ","BOLL","RSI","ATR"] }, "required": true },
      "start_date": { "type": "string", "required": false },
      "end_date": { "type": "string", "required": false }
    },
    "required": ["ts_code", "indicators"]
  }
}
```

**createMonitorRule**（创建盯盘规则）：

```json
{
  "name": "createMonitorRule",
  "description": "创建盯盘监控规则，触发时推送富媒体消息",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ts_code": { "type": "string", "required": true },
      "rule_type": { "type": "string", "enum": ["PRICE","CHANGE","TECHNICAL","MONEYFLOW","FINANCIAL","ANNOUNCEMENT","TOP_LIST","HK_HOLD"], "required": true },
      "rule_params": { "type": "object", "description": "规则参数，如 {\"threshold\":1700,\"direction\":\"below\"}", "required": true },
      "push_channels": { "type": "string", "default": "IN_APP" }
    },
    "required": ["ts_code", "rule_type", "rule_params"]
  }
}
```

### 4.3 工具输出格式

工具返回统一封装为 `ToolResult` 结构，包含数据、容器类型标记和元信息。模型拿到的是结构化数据，前端拿到的是带容器标记的渲染指令。

```java
public record ToolResult(
    String toolName,           // 工具名
    String containerType,      // 富媒体容器类型，如 STOCK_CARD / KLINE_CHART / REPORT_PREVIEW
    Object data,               // 结构化数据，前端按 containerType 解析
    String dataSource,         // 数据来源标注，如 "daily_basic 表 / Tushare daily_basic 接口"
    String dataTimestamp,      // 数据时间戳
    boolean success,           // 调用是否成功
    String errorMessage        // 失败时的错误信息
) {}
```

以 `queryDailyBasic` 为例，返回的 `data` 字段结构如下，前端按 `containerType = VALUATION_CARD` 渲染估值卡片 + 分位图：

```json
{
  "containerType": "VALUATION_CARD",
  "data": {
    "stockName": "贵州茅台",
    "tsCode": "600519.SH",
    "tradeDate": "20260805",
    "pe": 35.2,
    "pePercentile": 78,
    "pb": 11.8,
    "pbPercentile": 65,
    "dividendYield": 1.2,
    "totalMv": 2140000000000,
    "percentileSeries": [
      { "date": "20210805", "pe": 28.1 },
      { "date": "20220805", "pe": 31.5 }
    ]
  },
  "dataSource": "daily_basic 表 / Tushare daily_basic 接口",
  "dataTimestamp": "20260805 收盘"
}
```

### 4.4 工具注册与发现机制

Spring AI Alibaba 提供两种 Function Calling 注册方式：注解式和编程式。对话中枢采用**编程式批量注册**，因为 16 个工具需要统一的生命周期管理、错误处理和审计拦截，注解式难以集中管控。

注册通过 `FunctionCallbackFactory` 在启动时把所有工具的 `FunctionCallback` 实例注入 Spring 容器，ChatClient 配置时引用这些 bean：

```java
@Configuration
public class ToolRegistryConfig {

    @Bean
    public List<FunctionCallback> stockQueryTools(
            StockQueryService stockQueryService,
            FinancialQueryService financialQueryService) {
        return List.of(
            FunctionCallback.builder()
                .function("queryStockBasic", stockQueryService::queryBasic)
                .description("查询股票基本信息")
                .inputType(StockBasicQueryDTO.class)
                .build(),
            FunctionCallback.builder()
                .function("queryDailyBasic", stockQueryService::queryDailyBasic)
                .description("查询每日估值指标及历史分位")
                .inputType(DailyBasicQueryDTO.class)
                .build()
            // ... 其余 14 个工具
        );
    }
}
```

工具的发现机制面向模型侧。注册的 `FunctionCallback` 会被 Spring AI 自动转换为 OpenAI 兼容的 function schema，随 ChatClient 请求一起发给通义千问。模型根据 schema 决定是否调用工具及传什么参数。不是所有对话都注册全部 16 个工具--意图路由会按意图筛选工具子集，例如轻量查询只注册查询类工具（5 个），避免无关工具的 schema 污染 prompt 浪费 Token。

### 4.5 工具调用的错误处理策略

工具调用失败是常态而非异常，对话中枢为三类常见失败预设了处理策略：

| 失败类型 | 处理策略 | 对用户的表现 |
|---|---|---|
| 接口超时 | 重试 1 次（间隔 1s），仍失败则降级返回缓存数据或空结果 | "数据获取超时，展示的是最近缓存数据" |
| 数据不存在 | 返回 success=false + 明确原因（如该股票无此日期数据） | "该股票在 {date} 无交易日数据，是否查询最近交易日" |
| 参数错误 | 不重试，直接返回参数校验错误，由模型自行修正参数重调 | 模型收到错误后通常能自行修正 ts_code 格式 |

所有工具调用包裹在 `ToolCallTemplate` 中，统一拦截异常、记录耗时、写入审计日志。模板伪代码如下：

```
function callWithGuard(toolName, args):
    start = now()
    try:
        result = dispatch(toolName, args)        // 实际工具调用
        audit.record(success=true, duration=now()-start)
        return result
    catch TimeoutException:
        cached = cache.get(toolName, args)       // 尝试缓存降级
        if cached:
            return cached.withFlag(STALE)
        return ToolResult(success=false, error="数据获取超时")
    catch Exception as e:
        audit.record(success=false, error=e.message)
        return ToolResult(success=false, error=friendly(e))
```

### 4.6 工具调用审计日志

每次工具调用的输入、输出、耗时、模型消耗都写入 `ai_analysis_audit` 表。这张表在规划书第十章已定义，字段包括 user_id、conversation_id、user_query、intent、tools_called、tool_results、ai_response、model_name、tokens_input、tokens_output、latency_ms。

审计日志的写入是**异步**的，不阻塞对话主链路。通过 Spring 的 `@Async` + 事务性 outbox 模式，先把审计事件写入内存队列，由后台线程批量落库。这样即使审计写入失败也不影响用户对话体验。

审计日志的用途有三个：一是事后追溯某次分析的完整调用链（用户质疑某个结论时可回放）；二是统计工具调用成功率和平均耗时用于性能优化；三是核算 Token 成本用于配额管理（见第八章）。

---

## 五、Graph 多智能体编排

### 5.1 Spring AI Alibaba Graph 基本概念

Spring AI Alibaba Graph 借鉴 LangGraph 的设计，用三个基本概念编排多步骤任务：**节点（Node）**、**边（Edge）**、**状态（State）**。节点是一个执行单元，接收状态、执行逻辑、返回状态更新。边定义节点之间的流转关系，可以是固定边（A 执行完必定到 B）或条件边（根据状态字段决定下一步去哪）。状态是一个共享的数据容器，在节点之间传递，承载整个 Workflow 的中间产物。

一个 Graph 的定义大致如下：声明若干节点、声明节点间的边、指定入口节点。执行时从入口节点开始，按边的拓扑依次执行节点，每个节点读状态、写状态，直到到达终止节点。状态中可以携带"人类确认"标记，遇到时暂停执行等待外部输入，这就是 Human-in-the-loop 的基础。

### 5.2 18 种研究方法映射为 Graph 节点

规划书提到 18 种研究方法映射为 Graph 节点，Phase 1 先实现 4 种高频方法（tear-sheet / technical-analysis / event-anomaly / position-monitor），Phase 2 补齐全部 18 种。根据规划书的报告分类体系（个股深度、板块行业、选股筛选、持仓诊断、市场综述、定时任务）和盯盘规则类型，18 种研究方法节点设计如下：

| 序号 | 节点 ID | 研究方法 | 所属报告大类 | 核心数据依赖 |
|---|---|---|---|---|
| 1 | `tear-sheet` | 基本面速览 | 个股深度 | daily_basic + stock_basic |
| 2 | `technical-analysis` | 技术面研判 | 个股深度 | daily_quote + calculateIndicator |
| 3 | `valuation-analysis` | 估值分析（PE/PB 分位） | 个股深度 | daily_basic 历史 |
| 4 | `financial-statement` | 财务三表分析 | 个股深度 | income/balancesheet/cashflow |
| 5 | `moneyflow-analysis` | 资金面分析 | 个股深度 | moneyflow + hk_hold |
| 6 | `competitive-landscape` | 竞争格局 | 个股深度 | 行业成分 + 财务对比 |
| 7 | `dcf-valuation` | DCF 估值 | 个股深度 | cashflow + fina_indicator |
| 8 | `comparable-valuation` | 可比估值 | 个股深度 | 行业成分 + daily_basic |
| 9 | `event-anomaly` | 事件异动分析 | 个股深度 | top_list + 公告 + moneyflow |
| 10 | `earnings-interpretation` | 财报解读 | 个股深度 | income + fina_indicator |
| 11 | `industry-overview` | 行业综述 | 板块行业 | sw_daily + index_member |
| 12 | `sector-trend` | 板块行情趋势 | 板块行业 | sw_daily 历史 |
| 13 | `screening` | 选股筛选 | 选股筛选 | 全市场 daily_basic 多条件 |
| 14 | `portfolio-diagnosis` | 持仓诊断 | 持仓诊断 | user_portfolio + daily_quote |
| 15 | `position-monitor` | 持仓监控 | 持仓诊断 | user_portfolio + 实时行情 |
| 16 | `market-overview` | 大盘综述 | 市场综述 | 指数行情 + moneyflow |
| 17 | `hk-hold-analysis` | 北向资金分析 | 市场综述 | hk_hold + 行业分布 |
| 18 | `risk-assessment` | 风险评估 | 通用 | 财务 + 波动率 + 暴露 |

每个节点内部的结构是一致的：先调用若干 Function Calling 工具取数，再用 ChatClient（按节点复杂度选 qwen-plus 或 qwen-max）基于取到的数据生成结构化分析文本，最后把数据和文本一起写入 State 供后续节点和报告组装节点使用。

### 5.3 Workflow 编排模式

18 个节点通过四种编排模式组合成不同的 Workflow，覆盖从单方法到深度研究的各种用户请求：

**串联模式**是最基础的编排，节点按顺序依次执行，前一个的输出作为后一个的输入。例如个股深度研究的标准链路是 tear-sheet → technical-analysis → valuation-analysis → financial-statement → moneyflow-analysis → report-assemble，每个节点拿到前序节点的状态增强自己的分析。

**并行模式**用于互不依赖的节点同时执行，缩短整体延迟。例如"全面分析茅台"时，technical-analysis、valuation-analysis、moneyflow-analysis 三个节点都只依赖 tear-sheet 提供的 ts_code，彼此无依赖，可并行执行。并行通过 Graph 的 fan-out 边实现，执行完后 fan-in 汇聚到 report-assemble 节点。

**条件分支**根据状态字段的值决定走哪条边。例如财报解读 Workflow 中，先执行 `earnings-interpretation` 节点判断业绩是否超预期，如果超预期走 `event-anomaly` 分析市场反应，如果低于预期走 `risk-assessment` 评估风险，两条分支最终汇入 report-assemble。

**循环模式**用于迭代式分析，例如选股筛选后对结果逐只做 tear-sheet 快速扫描，筛选结果是一个列表，循环对每只股票执行 tear-sheet 节点。循环设置最大迭代次数避免无限执行，超过 20 只时分批处理。

### 5.4 Human-in-the-loop 设计

某些研究方法涉及主观判断或高风险操作，需要在关键节点暂停等待用户确认。典型场景是 DCF 估值--模型基于历史现金流算出折现结果后，应让用户确认或调整关键假设（增长率、折现率），再继续生成报告。

Human-in-the-loop 的实现基于 Graph 的状态暂停机制。在需要确认的节点后插入一个 `human-checkpoint` 节点，该节点把当前状态序列化存入 Redis，返回一个 `pending_confirmation` 状态并暂停 Graph 执行。前端收到暂停信号后渲染交互表单容器，展示待确认的假设和参数供用户修改。用户确认后前端提交修改后的参数，后端从 Redis 恢复状态、合并用户输入、恢复 Graph 执行。

```
节点 dcf-valuation 执行完毕，状态中写入:
  { dcf_result: {...}, pending_assumptions: {growth_rate: 0.15, discount_rate: 0.08} }

  ↓ 到达 human-checkpoint 节点，Graph 暂停

前端渲染:
┌─────────────────────────────────────────┐
│ DCF 估值需要确认关键假设：                  │
│ 未来 5 年营收增长率: [15%] ▼              │
│ 折现率 (WACC):      [8%]  ▼              │
│                                         │
│ 基于以上假设，DCF 估值结果: 1,850 元/股    │
│ [确认并继续] [调整假设]                    │
└─────────────────────────────────────────┘

  ↓ 用户点击「确认并继续」

Graph 恢复执行 → report-assemble 节点
```

为防止用户长时间不确认导致资源占用，pending 状态设置 30 分钟 TTL，超时自动终止 Workflow 并通知用户"分析已暂停，可重新发起"。

### 5.5 Graph 执行的容错设计

Graph 执行过程中节点可能失败，对话中枢的容错原则是**单节点失败不终止整个 Workflow**，而是降级跳过并标注。

每个节点包裹在 `NodeGuard` 中，捕获异常后向 State 写入一个 `node_status` 标记（`skipped` / `degraded` / `failed`）和错误摘要，然后让 Graph 继续执行下一个节点。report-assemble 节点在组装报告时会检查各节点的状态标记，对失败节点在报告中标注"该维度数据获取失败，建议稍后重试"，而非让整份报告生成失败。

节点超时是另一类容错场景。每个节点设置独立超时阈值：纯取数节点 10 秒、含模型调用的分析节点 30 秒、报告组装节点 60 秒。超时后按失败处理走上述降级逻辑。对于并行节点组，采用"最慢节点不阻塞"策略--设置一个组级超时，到点后已完成的节点正常汇聚，未完成的标记超时跳过。

### 5.6 典型 Workflow 编排示例

**示例一：全面分析茅台（多步骤研究）**

```
入口: tear-sheet
  │
  ├─(并行)─ technical-analysis ──┐
  ├─(并行)─ valuation-analysis ──┤
  ├─(并行)─ financial-statement ┤
  └─(并行)─ moneyflow-analysis ─┘
               │
               ▼
        report-assemble (qwen-max)
               │
               ▼
        数据校验 + 幻觉防护
               │
               ▼
        报告预览容器 → analysis_report 落库
```

这个 Workflow 包含 5 个分析节点并行 + 1 个报告组装节点，并行节点都只依赖 tear-sheet 提供的 ts_code。report-assemble 节点用 qwen-max，因为需要综合多维度数据生成高质量报告。典型耗时约 40-60 秒，期间前端显示各节点的执行进度。

**示例二：茅台财报后的异动分析（条件分支）**

```
入口: earnings-interpretation
  │
  ▼ (状态: earnings_surprise = "beat" / "miss")
  │
  ├─(beat)── event-anomaly ──────┐
  └─(miss)── risk-assessment ────┘
               │
               ▼
        moneyflow-analysis
               │
               ▼
        report-assemble
```

财报解读节点先判断业绩是否超预期，根据结果走不同的后续分析。无论哪条分支，最终都汇入 moneyflow-analysis 看市场资金面的反应，再进入报告组装。

**示例三：低估值高股息选股 + 逐只快速扫描（循环）**

```
入口: screening (条件: PE<20 且 ROE>15% 且 股息率>3%)
  │
  ▼ (状态: screening_result = [600519, 000858, ...] 最多 20 只)
  │
  └─(循环)─ tear-sheet × N
               │
               ▼
        report-assemble (生成选股报告)
```

选股筛选节点产出结果列表后，循环对每只股票执行 tear-sheet 快速扫描，最后组装成一份选股报告。循环设上限 20 只，超过的分批处理，每批 10 只并行。

---

## 六、RAG 知识库设计

### 6.1 三个向量索引

规划书第八章定义了三个向量索引，存储在 Redis Stack 的 RediSearch 中。三个索引服务于不同场景，数据来源和更新频率各不相同：

| 知识库 | 数据来源 | 用途 | 更新频率 |
|---|---|---|---|
| 投资方法论库 | 估值方法、技术分析方法、财务分析框架等领域知识 | 为 AI 分析提供方法论引用 | 定期重建 |
| 历史报告库 | 用户过往生成的分析报告 | 支持回溯查询和报告对比 | 实时更新 |
| 公司基本面库 | 基于 Tushare 财务数据生成的公司摘要 | 快速注入公司基本面背景 | 定期重建 |

### 6.2 投资方法论库

**数据来源**是预置的投资方法论知识文档，包括估值方法（DCF / 可比估值 / PE 分位法）、技术分析方法（MA / MACD / KDJ / BOLL 信号解读规则）、财务分析框架（杜邦分析 / 现金流质量 / 偿债能力）等。这些文档由运营人员维护，是相对静态的领域知识。

**索引构建流程**：文档按章节切分为 300-500 字的段落（chunk），每个段落附带元数据（方法论类别、适用场景）。段落文本经 embedding 模型转为向量，连同原文和元数据存入 RediSearch 索引。切分粒度选择 300-500 字是因为太短丢失上下文、太长检索精度下降。

**检索策略**：用户问题经 embedding 后在索引中做 Top-5 检索，相似度阈值设为 0.75，低于阈值的结果不注入 prompt。检索结果按相似度排序，取前 3 条注入。不单独做重排序模型，因为方法论库规模小（约数百条），Top-5 检索已足够精准。

**更新频率**：定期重建。运营人员更新方法论文档后，手动触发全量重建索引，重建期间旧索引继续服务，重建完成后原子切换。

### 6.3 历史报告库

**数据来源**是 `analysis_report` 表中用户生成的分析报告。每次 `generateReport` 工具生成新报告后，报告的摘要和关键结论会被实时写入此索引，支持用户后续"上次分析的茅台现在怎么样了"这类回溯查询。

**索引构建流程**：报告按"摘要 + 各章节标题 + 关键结论句"切分，而非全文逐句切分，因为用户回溯时关心的是结论而非细节。每段附加元数据（report_id、ts_code、industry、report_type、created_at），检索时可按元数据过滤。

**检索策略**：Top-5 检索 + 相似度阈值 0.7。检索结果注入 prompt 时附带报告生成时间，让 AI 知道这是"2026-08-03 的分析"而非实时数据，避免把历史报告结论当作当前事实。对于"对比"类请求，检索 Top-2 条同股票不同时间的报告，注入 prompt 让 AI 做横向对比。

**更新频率**：实时更新。报告生成后通过事件驱动异步写入索引，用户生成报告后立即就能在后续对话中检索到。

### 6.4 公司基本面库

**数据来源**是 Tushare 财务数据加工后的公司摘要。每个上市公司生成一段结构化摘要文本，包含主营业务、行业分类、最新财务关键指标、近三年业绩趋势。摘要由后端确定性代码生成而非 AI，确保数据准确。

**索引构建流程**：公司摘要整体作为一个 chunk（通常 200-400 字），附加元数据（ts_code、industry、最新报告期）。摘要文本经 embedding 存入索引。

**检索策略**：按 ts_code 精确过滤 + 语义检索兜底。当用户问"茅台"时先按 ts_code 精确命中公司摘要，当用户问"高端白酒龙头"时走语义检索匹配行业关键词。Top-3 检索，相似度阈值 0.7。

**更新频率**：定期重建。跟随财务数据更新周期，每季度财报季（4/8/10 月底）后批量重建，反映最新财务数据。

### 6.5 RAG 在对话中的应用流程

RAG 不是每次对话都触发，而是由意图路由按需触发。触发条件是：多步骤研究和单方法分析意图必触发 RAG（注入方法论），轻量查询不触发（只需取数），闲聊不触发，涉及历史报告回溯时触发历史报告库检索。

应用流程是：用户问题 → embedding → 并行检索三个知识库 → 合并去重 → 按相关度排序取 Top-N → 拼装为 context 注入 System Prompt → ChatClient 生成回复。三个知识库的检索是并行的，通过 CompletableFuture 并发执行，总延迟取决于最慢的一个库（通常 < 100ms，RediSearch 内存检索）。

注入 prompt 的格式固定为"参考知识"区块，与用户问题和对话历史用分隔符隔开，让模型明确哪些是检索来的背景知识、哪些是对话内容：

```
[System Prompt 基础部分]

## 参考知识（来自 RAG 检索，可能有时效性）
[方法论] PE 分位法的判断标准：低于 30% 为低估，高于 70% 为高估...
[历史报告] 2026-08-03 茅台深度分析结论：估值偏高，资金面转正...
[公司基本面] 贵州茅台，主营高端白酒，2025 年营收 1,560 亿...

## 对话历史
[最近 20 条消息摘要]

## 当前用户问题
全面分析茅台
```

### 6.6 向量模型选型

向量模型（embedding）选型在通义千问 embedding 模型与开源模型之间权衡。通义千问提供 `text-embedding-v2` 系列，与 Spring AI Alibaba 原生适配，调用方式与 ChatClient 一致，运维成本低，按量计费单价低。

选型建议是**主用通义千问 `text-embedding-v2`，预留开源模型 fallback**。理由有三：一是与 Spring AI Alibaba 生态一致，无需额外部署 embedding 服务；二是中文金融文本的 embedding 质量在主流商业模型中表现良好；三是三个知识库规模都不大（方法论数百条、历史报告数千条、公司基本面数千条），商业模型按量计费成本可控。

预留 fallback 的场景是：当 DashScope API 不可用时，降级到本地部署的开源 embedding 模型（如 bge-large-zh），已构建的索引需重建。因此索引层抽象出 `EmbeddingProvider` 接口，切换模型时只需更换实现类并重建索引，不影响检索逻辑。

embedding 维度统一为 1536（text-embedding-v2 默认），RediSearch 索引的 vector 字段按此维度定义。三个知识库共用一套 embedding 配置，避免多模型混用导致向量空间不一致。

---

## 七、AI 幻觉防护体系

### 7.1 幻觉防护的总体思路

规划书在风险章节提及 AI 幻觉风险但未详述防护方案。对话中枢的幻觉防护不是单一手段，而是五层纵深防御：数值层确保数字不出错、数据层确保引用可核对、Prompt 层约束行为边界、Advisor 层做合规过滤、审计层支持事后追溯。每一层防护独立生效，层层叠加。

### 7.2 数值层防护

数值层是第一道也是最硬的一道防线。**所有数值计算走确定性代码，AI 不得自行计算**。这条原则在第一章已确立，在幻觉防护体系中被具体化为三条实现约束：

第一，工具返回的数据是成品而非原料。`queryDailyBasic` 返回的是"PE 35.2、78% 分位"这样的计算结果，不是"close=1705、eps=48.4"这样的原始字段让模型自己算 PE。第二，分位数、均值、增长率等统计量由后端 Java 或 Python 计算服务算好，模型只引用不推导。第三，DCF 估值、可比估值等复杂计算完全在 Python 计算服务中完成，模型只拿到折现结果做解读。

数值层的校验发生在工具返回时：每个工具返回的数值字段都附带一个 `computedBy` 标记（`JAVA` / `PYTHON` / `CACHED`），标记为模型计算的字段会被拒绝注入 prompt。

### 7.3 数据层防护

数据层防护针对的是报告生成场景。AI 在生成报告文本时可能"引用"一个看起来合理但实际不存在的数字，例如把 PE 35.2 写成 35.8。数据层防护在 report-assemble 节点之后插入一个**数据校验环节**，比对 AI 文本中出现的数值与工具实际返回的数值。

校验逻辑用正则提取 AI 生成文本中的数值，与 State 中存储的工具返回值做匹配。匹配策略是允许四舍五入误差（如 35.2 写成 35.2 或 35 均算通过），但不允许数值捏造（如 35.2 写成 38.0 则标记为不一致）。发现不一致时，校验环节自动用工具返回值替换 AI 文本中的错误数字，并在审计日志中记录替换事件。连续 3 次以上不一致会触发告警，提示运营人员检查 prompt 或模型版本。

### 7.4 Prompt 层防护

System Prompt 中明确约束 AI 的行为边界，是成本最低但效果显著的防护层。对话中枢的 System Prompt 包含以下硬性约束：

```
你是一个 A 股投资分析助手。严格遵守以下规则：

1. 数据来源：你引用的所有数值必须来自工具调用返回的数据，
   不得自行计算、估算或编造任何数字。
2. 时效声明：引用数据时标注时间（如"截至 20260805 收盘"）。
3. 合规红线：不提供买卖建议，所有分析末尾标注"不构成投资建议"。
4. 不确定处理：当工具未返回数据时，明确说"未获取到该数据"，
   不得用历史数据或猜测填充。
5. 引用边界：只解读工具返回的字段，不得推测未提供的数据维度。
```

Prompt 层防护的局限是模型可能不遵守约束，因此它不能独立生效，必须与数据层校验配合。Prompt 是"事前提醒"，数据校验是"事后兜底"。

### 7.5 Advisor 层防护

Spring AI 的 **Advisor 机制**是对模型输入输出的拦截器链，适合做合规过滤和事实核查。对话中枢注册两个 Advisor：

**合规过滤 Advisor** 拦截模型输出，检查是否包含买卖建议类词汇（"建议买入""强烈推荐""满仓"等），命中时自动追加"不构成投资建议"声明或重写相关表述。这个 Advisor 在所有意图的回复上都生效。

**事实核查 Advisor** 专门用于报告生成场景，在 report-assemble 节点输出后触发，调用数据层校验逻辑（7.3 节），不通过则要求模型重新生成。Advisor 的执行顺序通过 `@Order` 注解控制，合规过滤在前、事实核查在后。

### 7.6 审计层防护

审计层是最后一道防线，也是唯一支持事后追溯的层。`ai_analysis_audit` 表记录每一次对话的完整调用链：用户原始问题、识别的意图、调用了哪些工具、工具返回了什么、AI 最终回复了什么、用了哪个模型、消耗了多少 Token、总耗时多少。

审计日志的查询场景包括：用户质疑某个结论时，运营人员通过 conversationId 回放完整调用链，定位是工具数据有误还是模型解读有误；定期抽样审计，统计幻觉发生率（数据校验不一致次数 / 总报告数），作为模型版本切换的决策依据。

审计日志设保留期 90 天，超过后归档到冷存储。90 天足够覆盖一个季度的运营复盘，冷存储按需检索，不占用在线存储。

---

## 八、模型选型与成本控制

### 8.1 模型分级使用策略

通义千问提供多个模型档位，对话中枢按意图复杂度分级使用，避免所有请求都用最贵的模型：

| 意图 | 模型档位 | 典型场景 | 选择理由 |
|---|---|---|---|
| 轻量查询 | qwen-turbo | PE 查询、行情卡片解读 | 任务简单，一句话解读，turbo 足够 |
| 选股筛选 | qwen-turbo | 条件筛选 + 结果简评 | 取数为主，模型只做简评 |
| 操作指令 | qwen-turbo | 任务/规则解析、确认容器 | 结构化解析，turbo 即可 |
| 闲聊兜底 | qwen-turbo | 通用对话 | 无数据分析需求 |
| 单方法分析 | qwen-plus | 技术面分析、估值分析 | 需要一定推理能力生成结构化文本 |
| 意图分类 | qwen-turbo | LLM 层意图识别 | 分类任务对模型能力要求低 |
| 偏好抽取 | qwen-turbo | 后台异步抽取用户偏好 | 抽取任务简单 |
| 摘要生成 | qwen-turbo | ChatMemory 窗口溢出摘要 | 摘要是压缩任务，turbo 足够 |
| 多步骤研究 | qwen-max | 深度报告生成、DCF 解读 | 需要强推理和长文本组织能力 |
| 报告组装 | qwen-max | report-assemble 节点 | 综合多维度数据生成高质量报告 |

模型分级通过 ChatClient 的 model 参数动态指定，意图路由在启动处理链时把模型档位作为参数传入。同一意图在不同节点可用不同模型，例如多步骤研究 Workflow 中，分析节点用 qwen-plus，report-assemble 节点用 qwen-max。

### 8.2 Token 用量预估

以三个典型对话场景估算 Token 消耗，帮助预估日均成本：

**场景一：轻量查询（茅台今天的 PE）**。System Prompt 约 300 Token，对话历史约 200 Token，工具返回数据约 150 Token，输出约 50 Token。单次约 700 Token，其中输入 650 / 输出 50。

**场景二：单方法分析（茅台技术面怎么样）**。System Prompt 约 400 Token，对话历史约 400 Token，工具返回数据约 800 Token（含指标序列），输出约 300 Token。单次约 1900 Token，其中输入 1600 / 输出 300。

**场景三：多步骤研究（全面分析茅台）**。5 个并行分析节点各约 1500 Token，report-assemble 节点输入（5 节点输出汇总）约 3000 Token + RAG 检索注入约 500 Token，输出约 1500 Token。总计约 12500 Token，其中输入 11000 / 输出 1500。

按通义千问定价（turbo 约 0.3 元/百万输入 Token、plus 约 0.8 元/百万、max 约 2 元/百万，输出价格约为输入 3 倍）粗算：场景一约 0.0005 元，场景二约 0.003 元，场景三约 0.05 元。假设日均 1000 次对话（轻量 60%、单方法 25%、多步骤 15%），日均模型成本约 13 元，月均约 400 元。实际成本受促销和实际调用结构影响，此处仅为量级估算。

### 8.3 成本控制机制

**每日 Token 上限**：系统级设置每日 Token 总量上限（如 500 万 Token），超过后非紧急意图降级到更便宜的模型或返回"今日分析额度已用完"提示。紧急意图（如操作指令、盯盘触发）不受上限限制。

**用户级配额**：免费用户每日 20 次分析（含 5 次多步骤研究），付费用户提升至 100 次。配额计数基于 `ai_analysis_audit` 表按日聚合，实时查询并缓存到 Redis。配额超限时引导用户升级或等待次日刷新。

**缓存策略**：对相同参数的查询结果做短时缓存（TTL 5 分钟，覆盖盘中价格波动间隔）。缓存键为 `tool_name + 参数 hash`，命中缓存时直接返回 ToolResult 并标注 `CACHED`，不调用底层接口也不触发模型解读（解读文本一并缓存）。缓存对轻量查询场景的降本效果最显著，因为"茅台 PE"这类高频问题会被大量用户重复提问。

### 8.4 模型降级方案

主模型不可用时的降级策略按故障范围分两级：

**单模型降级**：qwen-max 不可用时，多步骤研究降级到 qwen-plus 并在回复中标注"当前为降级模式，报告质量可能略低"。qwen-plus 不可用时，单方法分析降级到 qwen-turbo 但只返回数据卡片不做深度解读。降级通过 ChatClient 的 fallback 机制实现，配置主备模型对，主模型连续失败 3 次后切换到备模型，定时探测主模型恢复后切回。

**整体不可用**：DashScope API 整体不可用时，对话中枢进入**只读模式**：查询类工具（取数）仍可用，但所有需要模型生成的回复降级为"数据获取正常，AI 解读服务暂时不可用，已为你展示原始数据"。只读模式下操作指令仍能正常处理（规则解析降级为前端表单引导用户手动填写参数），保证核心功能不中断。整体不可用的探测通过定时健康检查（每 30 秒 ping 一次 DashScope），连续 3 次失败进入只读模式，恢复后自动退出。
