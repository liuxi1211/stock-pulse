# 策略状态管理修复 + 回测中心可用性修复

## 概述

用户反馈："策略管理每个策略都有一套状态（verified 等），但好像没有功能去维护管理，导致回测中心根本不可用"。

经过探索，**问题由两个独立缺陷叠加**：

1. **回测中心直接根因（前端 Bug）**：`backtest-new.js` 用 `v.status` 过滤版本列表，但后端 `StrategyVersionDTO` 从未定义 `status` 字段，导致 `v.status` 永远为 `undefined`，所有版本都被过滤掉，回测中心永远显示"该策略没有 VERIFIED/ACTIVE 版本，无法回测"。**这是回测中心"根本不可用"的真正原因**，与策略状态管理 UI 缺失无关。
2. **策略状态管理 UI 缺失（用户提到的"没有功能去维护管理"）**：后端 `PUT /api/strategies/{id}/status` 接口和 `StrategyStatusEnum.canTransitionTo` 状态机已完整实现，但前端只有"保存草稿"（VERIFIED→DRAFT 的副作用）和"归档"（DELETE 接口绕过状态机）两个间接入口，**完全没有"激活""下线""退回草稿"的显式 UI**。用户无法让策略进入 ACTIVE 状态，列表页"激活中"统计卡片永远显示 0。

**用户已确认修复范围**：两者都修 + 状态管理 UI 范围为"核心流转"（激活 + 下线 + 退回草稿）。

## 现状分析

### 后端现状（已完整，无需改动）

- [StrategyStatusEnum.java](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/StrategyStatusEnum.java) 第 22-94 行：4 个状态 + `canTransitionTo` 状态机
  - 合法流转：DRAFT→VERIFIED、VERIFIED→DRAFT/ACTIVE、ACTIVE→ARCHIVED
  - ARCHIVED 为终态
- [QuantStrategyController.java](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/QuantStrategyController.java) 第 131-139 行：`PUT /api/strategies/{id}/status` 接口完整实现
- [StrategyServiceImpl.java](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StrategyServiceImpl.java) 第 291-308 行：`updateStatus` 调用 `canTransitionTo`，非法流转抛 `STRATEGY_INVALID_STATUS_TRANSITION`
- [BacktestServiceImpl.java](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/BacktestServiceImpl.java) 第 172-179 行：**正确**地用主表 `strategy.getStatus()` 校验回测资格
- [StrategyVersionDTO.java](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/dto/strategy/StrategyVersionDTO.java)：**只有 `versionNo/configJson/changelog/createdAt` 四个字段，无 `status` 字段**
- [StrategyServiceImpl.toListVersionDTO](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StrategyServiceImpl.java) 第 671-677 行：不设置 status

### 前端现状（存在 Bug + UI 缺失）

- [backtest-new.js](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/static/js/backtest-new.js) 第 19 行：`ALLOWED_VERSION_STATUS = ['VERIFIED', 'ACTIVE']`
- [backtest-new.js](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/static/js/backtest-new.js) 第 126-135 行：**Bug 所在**，用不存在的 `v.status` 过滤版本
- [backtest-new.js](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/static/js/backtest-new.js) 第 144-155 行：渲染时还使用 `v.status` 做样式和文本（第 146、151 行）
- [strategy-list.js](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/static/js/strategy-list.js) 第 327-336 行：卡片底部只有 4 个按钮（编辑/版本/回测/归档），**无状态切换按钮**
- [strategy-list.js](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/static/js/strategy-list.js) 第 464-466 行：回测按钮点击只弹 toast"回测中心即将开放"
- [editor.html](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/templates/quant/strategies/editor.html) 第 28 行：状态徽章仅展示，无操作
- [editor.html](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/templates/quant/strategies/editor.html) 第 691-695 行：底部按钮只有"取消/保存草稿/保存新版本"
- [strategy-editor.js](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/static/js/strategy-editor.js) 第 876-882 行：状态徽章渲染逻辑
- 项目已用 Bootstrap 5，可直接用 `dropdown` 组件

## 修复方案

### 修复 1：回测中心版本过滤 Bug（后端补 status 字段）

**方案选型**：在三种方案中选择 **方案 A（后端补 status 字段）**：
- 方案 A：后端 `StrategyVersionDTO` 补 `status` 字段，从主表 `strategy.getStatus()` 透传给每个版本。前端逻辑改动最小。
- 方案 B：前端去掉 `v.status` 过滤，改为读策略主表状态。
- 方案 C：后端在版本列表接口直接过滤。

选 A 的理由：①前端 `backtest-new.js` 第 146、151 行还用 `v.status` 做样式和文本展示，方案 B 需要同步改这些地方；②语义上"版本状态 = 策略主表状态"实际就是当前业务模型（版本表本身不存独立状态，后端 `BacktestServiceImpl` 也用主表状态判定）；③改动集中、风险最低。

**后端改动**：

1. **[StrategyVersionDTO.java](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/dto/strategy/StrategyVersionDTO.java)**：
   - 新增 `private String status;` 字段，带 `@Schema(description = "策略状态（DRAFT/VERIFIED/ACTIVE/ARCHIVED，自主表透传）")`

2. **[StrategyServiceImpl.java](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StrategyServiceImpl.java)**：
   - `toListVersionDTO(QuantStrategyVersionDO v)` 改为 `toListVersionDTO(QuantStrategyVersionDO v, String status)`，方法体内 `dto.setStatus(status);`
   - `toDetailVersionDTO(QuantStrategyVersionDO v)` 同样改为接收 `status` 参数并透传
   - `listVersions(String strategyId)`（第 313-320 行）：在已查到 `strategy` 后，把 `strategy.getStatus()` 传给 `toListVersionDTO`
   - `getVersion(String strategyId, Integer versionNo)`：同样把 `strategy.getStatus()` 传给 `toDetailVersionDTO`

**前端改动**：

3. **[backtest-new.js](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/static/js/backtest-new.js)**：
   - 第 19 行常量保留
   - 第 126-135 行的 `v.status` 过滤逻辑**无需改动**（后端补字段后即可正常工作）
   - 第 146、151 行的 `v.status` 样式和文本展示**无需改动**
   - 仅需验证：后端补字段后，前端能正确显示版本列表

### 修复 2：策略状态管理 UI（核心流转）

**UI 设计**：用 Bootstrap 5 `dropdown` 组件做"状态管理"下拉菜单，避免卡片底部按钮过多。

#### 列表页改动

4. **[strategy-list.js](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/static/js/strategy-list.js)**：
   - 在 `renderCard`（第 280-338 行附近）的 `str-card-actions` 区域，**在"归档"按钮前**插入一个"状态管理"下拉按钮：
     - 图标：`bi-arrow-repeat` 或 `bi-sliders`
     - 根据 `s.status` 动态生成下拉菜单项：
       - `VERIFIED`：显示"激活"（→ACTIVE）、"退回草稿"（→DRAFT）
       - `ACTIVE`：显示"下线"（→ARCHIVED）
       - `DRAFT` / `ARCHIVED`：不渲染下拉按钮（DRAFT 需要先校验，ARCHIVED 终态）
     - 下拉菜单项使用 `data-action="status-change" data-id="..." data-target="ACTIVE"` 属性
   - 在 `bindEvents` 的 `strategyGrid` click 监听（第 456-473 行）中新增分支：
     - `action === 'status-change'`：弹出 `StockApp.confirm` 二次确认，文案"确认将策略「{name}」{动作Label}？"
     - 确认后调用 `PUT /api/strategies/{id}/status`，body 为 `{status: target}`
     - 成功：toast 提示 + `load()` 刷新列表 + `loadStats()` 刷新统计
     - 失败：toast 错误信息（含 `STRATEGY_INVALID_STATUS_TRANSITION` 等）

5. **顺带修复回测入口未接通**：
   - [strategy-list.js](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/static/js/strategy-list.js) 第 291 行：`backtestDisabled` 逻辑修正为 `s.status === 'DRAFT' || s.status === 'ARCHIVED'`（DRAFT/ARCHIVED 不可回测，与后端口径一致；去掉对 `lastReturnPct` 的依赖，因为是否曾回测过不影响"能否发起新回测"）
   - 第 464-466 行：`action === 'backtest'` 分支改为 `location.href = StockApp.contextPath + '/quant/backtests/new?strategyId=' + encodeURIComponent(id);`

#### 编辑器改动

6. **[editor.html](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/templates/quant/strategies/editor.html)**：
   - 在第 34-36 行 `str-page-actions` 区域（现有"取消"按钮旁），新增一个"状态管理"下拉按钮：
     ```html
     <div class="btn-group" id="statusActionWrap" style="display:none;">
       <button type="button" class="btn btn-outline-primary btn-sm dropdown-toggle" data-bs-toggle="dropdown">
         <i class="bi bi-arrow-repeat"></i> 状态管理
       </button>
       <ul class="dropdown-menu dropdown-menu-end" id="statusActionMenu"></ul>
     </div>
     ```
   - 默认 `display:none`，由 JS 根据 `meta.status` 决定是否显示和菜单项内容

7. **[strategy-editor.js](file:///c:/Users/wudy/IdeaProjects/stock-pulse/stock-watcher/src/main/resources/static/js/strategy-editor.js)**：
   - 在 `applyMeta`（第 876-882 行附近，渲染状态徽章之后）新增逻辑：调用新方法 `renderStatusActions(meta.status)` 填充 `#statusActionMenu`
   - 新增方法 `renderStatusActions(status)`：
     - 根据 `status` 生成菜单项（与列表页一致：VERIFIED 显示"激活""退回草稿"；ACTIVE 显示"下线"；DRAFT/ARCHIVED 不显示按钮）
     - 给每个菜单项绑定 click 事件
   - 新增方法 `changeStatus(target, label)`：
     - `StockApp.confirm` 二次确认
     - `fetch PUT /api/strategies/{id}/status`，body `{status: target}`
     - 成功：toast + 重新加载页面（`location.reload()`）或重新拉取 meta 更新徽章
     - 失败：toast 错误信息

### 修复 3（可选小修）：DELETE 接口语义

**不在本次范围内**：用户明确选择"核心流转"，未选"完整状态机"。DELETE 接口绕过状态机的问题保留现状（DRAFT/VERIFIED 也可被 DELETE 直接归档），不强制走状态机。仅记录在 Assumptions。

## 具体改动文件清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `stock-watcher/src/main/java/com/arthur/stock/dto/strategy/StrategyVersionDTO.java` | 新增字段 | 加 `private String status;` |
| `stock-watcher/src/main/java/com/arthur/stock/service/impl/StrategyServiceImpl.java` | 改方法签名 | `toListVersionDTO`/`toDetailVersionDTO` 加 `status` 参数；`listVersions`/`getVersion` 透传主表状态 |
| `stock-watcher/src/main/resources/static/js/backtest-new.js` | 验证为主 | 后端补字段后前端无需改动（保留现有 `v.status` 逻辑） |
| `stock-watcher/src/main/resources/static/js/strategy-list.js` | 新增 UI + 修复 | 卡片底部加状态管理下拉；回测按钮跳转修复；`backtestDisabled` 逻辑修正 |
| `stock-watcher/src/main/resources/templates/quant/strategies/editor.html` | 新增 HTML | 页头加状态管理下拉按钮 |
| `stock-watcher/src/main/resources/static/js/strategy-editor.js` | 新增方法 | `renderStatusActions`、`changeStatus`；在 `applyMeta` 调用 |

## 假设与决策

1. **方案 A（后端补 status 字段）**：理由见"修复 1"段。语义上"版本状态 = 策略主表状态"，与后端 `BacktestServiceImpl` 判定口径一致。
2. **UI 形式用下拉菜单**：避免卡片底部按钮过多（现有 4 个 + 新增 3 个会拥挤）。用 Bootstrap 5 `dropdown` 组件，项目已依赖 Bootstrap。
3. **列表页 DRAFT/ARCHIVED 不显示状态管理按钮**：DRAFT 需要先保存配置通过校验才能 VERIFIED；ARCHIVED 是终态。
4. **退回草稿（VERIFIED→DRAFT）从列表页也可触发**：虽然语义上"退回草稿"通常意味着要继续编辑，但用户明确要求"核心流转"包含此项，列表页提供入口便于操作。
5. **编辑器状态切换成功后用 `location.reload()`**：编辑器状态多，重新加载最稳妥（避免局部更新遗漏）。
6. **不修 DELETE 接口语义**：用户范围选"核心流转"，不强制 DELETE 走状态机。保留现状（任何状态可 DELETE 归档）。
7. **不补审计日志**：用户范围未含此项。状态变更只更新 `updated_at`。
8. **列表页回测按钮跳转修复**：顺带修，因为用户反馈"回测中心不可用"，列表页回测按钮弹"即将开放"toast 加剧了不可用感。

## 验证步骤

### 后端验证

1. 启动 stock-watcher 服务
2. 准备一个 VERIFIED 状态的策略（可通过新建策略带 configJson 自动获得）
3. 调用 `GET /api/strategies/{id}/versions`，验证返回的每个版本对象都包含 `status` 字段，值为 `"VERIFIED"`
4. 调用 `PUT /api/strategies/{id}/status`，body `{"status":"ACTIVE"}`，验证返回 200 且策略状态变为 ACTIVE
5. 再次调用 `GET /api/strategies/{id}/versions`，验证 `status` 字段值为 `"ACTIVE"`
6. 调用 `PUT /api/strategies/{id}/status`，body `{"status":"DRAFT"}`，验证返回 400 + `STRATEGY_INVALID_STATUS_TRANSITION`（ACTIVE 不可直接回 DRAFT）
7. 调用 `POST /api/backtest/run`，对一个 VERIFIED 策略发起回测，验证不再返回 `BACKTEST_STRATEGY_VERSION_INVALID`

### 前端验证

1. 打开策略列表页 `/quant/strategies`
2. 选一个 VERIFIED 策略的卡片，验证"状态管理"下拉按钮存在，菜单含"激活""退回草稿"
3. 点击"激活"，确认弹窗，验证策略状态变为 ACTIVE，统计卡片"激活中"+1
4. 选一个 ACTIVE 策略，验证下拉菜单含"下线"
5. 点击"下线"，验证策略变为 ARCHIVED（注：实际是 DELETE 流程，但通过状态机接口走 ACTIVE→ARCHIVED）
6. 选一个 DRAFT 策略，验证不显示"状态管理"下拉
7. 点击卡片"回测"按钮，验证跳转到 `/quant/backtests/new?strategyId=...`（不再是 toast）
8. 打开回测中心 `/quant/backtests/new`，选一个 VERIFIED 策略，验证版本列表能正常显示（不再显示"没有 VERIFIED/ACTIVE 版本"）
9. 选版本后能进入步骤 2，最终能提交回测
10. 打开编辑器 `/quant/strategies/{id}/edit`，验证页头"状态管理"下拉按钮存在，菜单项与状态匹配
11. 点击"激活"，确认弹窗，验证页面刷新后状态徽章变为 ACTIVE

### 回归验证

1. 现有"保存草稿"按钮（编辑器）仍能正常把 VERIFIED 退回 DRAFT
2. 现有"归档"按钮（列表页 DELETE）仍能正常归档
3. 现有"保存新版本"按钮（编辑器）仍能把 DRAFT 升级为 VERIFIED
4. 现有状态筛选、统计卡片仍正常工作
