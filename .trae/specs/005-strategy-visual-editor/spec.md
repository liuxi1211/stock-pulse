# 策略可视化编辑模式与因子库联动 Spec

## Why

策略管理（004）编辑器当前对「选股范围」「买入信号」「卖出信号」「出场规则」等条件树采用 textarea 手写 JSON 的录入方式（对应 Schema §4 的递归 ConditionTree / CompareLeaf / ExpressionNode 结构）。该结构嵌套深、节点形态多（4 种 ExpressionNode）��**不熟悉 Schema 规则的用户几乎无法正确录入**，录入门槛极高。

而 003 选股中心（screener.js）已经实现并验证了一套可视化 ConditionTree 条件构建器（renderNode / addLeaf / addGroup / normalizeTree），产出的正是同一套 engine ConditionTree 结构。策略编辑器当前却没有复用它。

本次变更：① 在策略编辑器新增一种**可视化录入模式**（复用/抽取 003 的条件树组件），与现有 JSON 录入模式**双向互通**；② 预留 **AI 录入模式**入口（本期不接入，仅留 UI 入口与接口契约）；③ 打通**因子库/选股方案 → 策略编辑器**的跳转联动，把已配置好的选股方案直接带入策略编辑器。

## What Changes

### 新增：策略编辑器录入模式切换
- 在策略编辑器（editor.html / strategy-editor.js）的「选股范围」「买入信号」「卖出信号」「出场规则」等含条件树的 Tab 区域，新增**录入模式切换器**（Tab 或 Segmented Control）：`可视化` / `JSON` / `AI（敬请期待）`。
- **JSON 模式**：保留现有 textarea + 右侧 JSON 预览的行为不变（向后兼容）。
- **可视化模式**：把条件树渲染为可交互的规则树（逻辑组 AND/OR + 比较叶子 + 因子/值/引用操作数），支持增删节点、切换 operator、选择因子、填参数、选比较器。逻辑移植自 003 screener.js 的条件树构建器。
- **AI 模式**：本期仅占位（disabled 或「敬请期待」提示），不接入任何后端；定义好输入输出契约（自然语言文本 → ConditionTree JSON），便于后续接入。

### 新增：两种模式的双向互通
- **可视化 → JSON**：可视化模式下的任何编辑实时同步到 `state` 对应路径，右侧 JSON 预览即时更新。
- **JSON → 可视化**：JSON 模式下编辑 textarea 后切回可视化模式（或失焦时），解析 JSON 并渲染为规则树；解析失败给出明确错误提示并阻止切换（不破坏已有 state）。
- 两种模式操作的是**同一份 `state` 数据**（同一棵 ConditionTree），不存在双份状态；模式切换只是「视图」切换，不是「数据」切换。

### 新增：因子库 / 选股方案 → 策略编辑器 跳转联动
- 在因子库页面（factor-library.html）或选股中心（screener.html���配置好选股方案后，提供**「作为策略选股范围」**跳转按钮，点击后跳转到策略编辑器新建页（`/quant/strategies/new`），并把方案的 `screen_config`（universe / stocks / conditions / ranking / filters / top_n）预填入策略编辑器的 state（对应 `strategy_config.screen_config`）。
- 跳转通过 URL query param（如 `?screenSource=<planId>` 或 `?importScreen=<base64>`）承载，策略编辑器 init 时读取并异步拉取方案 JSON 后注入 state，注入后在选股范围 Tab 高亮提示���已从选股方案导入」。

### 不变（明确范围边界）
- **后端 Schema / 校验链路不变**：engine 的 `StrategyConfigModel` / `StrategyValidator` / `POST /python/v1/strategies/validate` 完全不动；可视化模式产出的 JSON 与 JSON 模式手写的 JSON 走**同一条校验链路**，口径完全一致。
- **持久化结构不变**：`quant_strategy` / `quant_strategy_version` 表结构、`config_json` 字段、版本管理、乐观锁均不动。保存的仍是同一份 `strategy_config` JSON。
- **不实现 AI 后端**：AI 模式仅前端占位。

## Impact

- **Affected specs**:
  - 004-strategy-management（策略编辑器：新增可视化模式 + 模式切换 + 跳转导入；是本次主要改动面）
  - 003-multi-factor-screener（条件树构建器被抽取为可复用组件；新增「跳转到策略」按钮）
  - 002-standard-factor-library（因子库页面新增「跳转到策略」入口；因子元数据本身不变）
- **Affected code**:
  - 前端：`templates/quant/strategies/editor.html`、`static/js/strategy-editor.js`、`static/css/strategy.css`
  - 前端：新建可复用条件树组件（从 `static/js/screener.js` 抽取，如 `static/js/condition-tree-builder.js` + 对应 css）
  - 前端：`templates/pages/factor-library.html`、`templates/pages/screener.html` 及其 JS（新增跳转按钮）
  - 后端（watcher Java）：`PageController.java`（跳转路由已存在 `/quant/strategies/new`，仅需确认 query param 透传）；可选新增「按 planId 取 screen_config」的复用接口（选股中心已有 `GET /screener/plans/{id}`，直接复用，无需新接口）
  - 后端（engine Python）：**无改动**

## ADDED Requirements

### Requirement: 策略编辑器录入模式切换
策略编辑器中所有承载条件树的区域（选股范围 conditions、买入信号 buy、卖出信号 sell、出场规则 exit.rules[*].condition）SHALL 提供三种录入模式的切换器：`可视化`、`JSON`、`AI（敬请期待）`。

#### Scenario: 默认进入可视化模式
- **WHEN** 用户进入策略编辑器（新建/编辑/模板任一模式）
- **THEN** 条件树区域默认以「可视化模式」呈现规则树（若该条件树为空，显示空态 + 「添加条件」按钮）

#### Scenario: 切换到 JSON 模式
- **WHEN** 用户从可视化模式切换到 JSON 模式
- **THEN** 条件树区域切换为 textarea，内容为当前 state 对应条件树的格式化 JSON；右侧 JSON 预览保持同步

#### Scenario: AI 模式占位
- **WHEN** 用户点击「AI」模式
- **THEN** 显示「AI 录入模式敬请期待」占位提示，不提供任何可交互输入，不修改 state

### Requirement: 可视化条件树编辑器
可视化模式 SHALL 提供完整的条件树交互编辑能力，复用 003 选股中心已验证的条件树构建逻辑。

#### Scenario: 添加逻辑组与叶子
- **WHEN** 用户在可视化模式下点击「添加条件」/「添加分组」
- **THEN** 在当前层级追加一个比较叶子节点（type=compare）/ 逻辑组节点（operator=AND），并即时同步到 state

#### Scenario: 编辑比较叶子
- **WHEN** 用户设置叶子的左操作数（因子/值/引用）、比较器、右操作数
- **THEN** 节点更新并即时同步到 state；因子选择器从 `/constants` 加载的 factorKey 白名单中选择

#### Scenario: 多输出因子强制选 output_index
- **WHEN** 用户选择了多输出因子（如 MACD/BOLL/KDJ）
- **THEN** 编辑器 SHALL 提示并要求选择 output_index（与 Schema §4.5 / validator MULTI_OUTPUT_REQUIRES_INDEX 对齐）

### Requirement: 可视化与 JSON 双向互通
两种模式 SHALL 操作同一份 state 数据，切换时双向同步，保证数据唯一性。

#### Scenario: 可视化编辑实时反映到 JSON
- **WHEN** 用户在可视化模式下修改任意节点
- **THEN** state 即时更新，右侧 JSON 预览与 JSON 模式 textarea 内容（切换后）同步反映修改

#### Scenario: JSON 编辑解析后反映到可视化
- **WHEN** 用户在 JSON 模式修改 textarea 并切回可视化模式（或 textarea 失焦）
- **THEN** 系统 JSON.parse 文本，成功则用解析结果替换 state 对应条件树并渲染规则树；失败则提示错误行/原因，**保留 state 不变**，阻止切换

### Requirement: 因子库/选股方案 → 策略编辑器 跳转导入
用户在因子库或选股中心配置好选股方案后，SHALL 能一键跳转到策略编辑器新建页并预填 screen_config。

#### Scenario: 从选股方案跳转
- **WHEN** 用户在选股中心某方案详情点击「作为策略选股范围」按钮
- **THEN** 浏览器跳转到 `/quant/strategies/new?screenSource=<planId>`；策略编辑器 init 时读取 query param，异步拉取该方案的 screen_config，注入到 `state.screen_config`，并在选股范围 Tab 显示「已从选股方案『XXX』导入」提示

#### Scenario: 导入不影响其它 Tab
- **WHEN** 跳转导入完成
- **THEN** 仅 `screen_config` 被填充，`trading_config` / `backtest_config` 保持默认值；用户可继续在其它 Tab 配置

## MODIFIED Requirements

### Requirement: 策略编辑器条件树录入（原 004：仅 JSON textarea）
策略编辑器的条件树录入方式从「单一 JSON textarea」扩展为「可视化 / JSON / AI 三模式可切换」，三模式共享同一份 state。原有的 JSON textarea 行为（含「插入因子」下拉、JSON.parse 预校验、engine 校验错误按 Tab 定位标红）全部保留并作为「JSON 模式」存在。校验链路（前端预校验 + engine `POST /python/v1/strategies/validate`）对三模式产出的 JSON 一视同仁。
