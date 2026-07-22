# 自选股模块功能补齐 - Product Requirement Document

## Overview
- **Summary**: 对现有自选股页面进行全面功能补齐，扩展 `sys_watchlist` 表结构，新增分组管理、价格提醒、搜索添加、多维排序、批量操作、mini K 线、列扩展等 10 项能力，将自选股从简单列表升级为老股民管理个人关注股池的专业工具。
- **Purpose**: 解决当前自选股功能单薄、只能基础增删查的问题，打造围绕「我的自选」的个性化管理闭环——分组归类、价格提醒、多维指标一览、无缝下钻个股诊断，让老股民管理几十只甚至上百只自选股不混乱，不用一直盯盘。
- **Target Users**: 3 年以上经验的 A 股散户/老股民，自选股池 30-80 只，习惯按行业分组管理，需要价格提醒辅助决策的个人投资者。

## Goals
- **目标一**：扩展 `sys_watchlist` 表结构（新增 group_id/note/target_price_high/target_price_low/sort_order 5 字段 + 索引），新建 `sys_watchlist_group` 自定义分组表，为分组管理和价格提醒奠基。
- **目标二**：实现分组管理能力，支持按申万一级行业自动归类 + 用户自定义分组，支持拖拽排序、跨分组拖拽、分组 CRUD，让老股民按行业/主题管理自选股不混乱。
- **目标三**：实现价格提醒能力，支持目标价模式和涨跌幅模式两种设置方式，前端 60 秒轮询比对，触发时顶部通知 + toast + 行高亮三重提醒，解放盯盘时间。
- **目标四**：补齐列表体验——顶部搜索框集成 SearchSuggest 直接添加、4 种排序方式切换、批量移除/批量改分组、股票代码点击跳转个股诊断，形成「添加→管理→下钻」闭环。
- **目标五**：扩展列表展示维度——新增行业/市值/PE/PB/换手率 5 列 + 30 日 mini K 线 sparkline，让老股民在列表页一眼看全多维指标与趋势。
- **目标六**：前端工程化——抽出独立 `watchlist.js` 文件，为后续功能迭代打下可维护基础。

## Non-Goals (Out of Scope)
- **实时盘中 WebSocket 推送**：价格提醒采用前端轮询方式，不做 WebSocket 实时推送。
- **跨账户自选股共享**：自选股是用户私有数据，不做共享/协作/导出给他人。
- **自选股策略回测**：策略回测归回测中心，自选股只做股池管理，不做组合回测。
- **自选股收益统计**：组合收益分析归仪表盘，不做自选股组合累计收益曲线。
- **自定义预警条件组合**：一期只做目标价/涨跌幅阈值，不做 MACD 金叉、量比突增等复杂条件预警。
- **自选股数据反向写入行情库**：自选股只读写 `sys_watchlist` 项目自有表，不写入任何 tushare 行情表。
- **移动端响应式适配**：自选股页面是 PC 端专业工具，不做手机端适配。
- **自选股历史快照**：不记录每日自选股池快照，不做历史回溯。

## Background & Context
- **现有状态**：自选股路由 `/page/watchlist` 已在侧边栏菜单，页面骨架存在但功能单薄——仅展示基础行情列表（代码/名称/最新价/涨跌额/涨跌幅/成交量）+ 移除按钮。`sys_watchlist` 表仅有 id/user_id/stock_code/created_at 4 个字段。`WatchlistController` 只有查询/添加/移除 3 个接口。前端脚本内联在 HTML 中。
- **数据依赖**：daily_quote（行情+30日收盘）、daily_basic（PE/PB/换手率/市值）、stock_basic（名称/拼音）、sw_industry + sw_industry_member（行业分类）均已有数据，自选股侧只消费，不新增 tushare 接口。
- **模块依赖**：弱依赖个股诊断（017）提供 `/page/stock-detail/{code}` 路由、弱依赖板块行情（015）提供行业列表接口、强依赖已有 `SearchSuggest` 组件 + 联想接口。
- **技术约束**：数据单源性（engine 不触库，本模块全部在 watcher 侧实现）、MySQL + SQLite 双 schema 同步、三主题切换兼容、遵循 Java/前端编码规范。

## Functional Requirements
- **FR-1**: 扩展 `sys_watchlist` 表结构，新增 group_id/note/target_price_high/target_price_low/sort_order 5 字段 + idx_sys_watchlist_group 索引；新建 `sys_watchlist_group` 自定义分组表。同步更新 MySQL + SQLite + 注释三份 schema。
- **FR-2**: 价格提醒功能——支持目标价模式（上限/下限）和涨跌幅模式（涨幅/跌幅阈值），前端 60 秒轮询比对，触发时顶部通知条 + toast + 行高亮三重提醒，5 分钟去重，后台 tab 降频为 5 分钟。
- **FR-3**: 分组管理——左侧分组栏显示「全部」+ 申万一级行业自动分组 + 用户自定义分组 + 「未分组」；支持自定义分组 CRUD、重命名、删除；支持表格行拖拽排序、跨分组拖拽。
- **FR-4**: 股票代码点击跳转个股诊断——列表「股票代码」列改为链接，点击跳转 `/page/stock-detail/{code}`；个股诊断未上线时降级为纯文本。
- **FR-5**: 集成 SearchSuggest 直接添加——页面顶部加搜索框，复用 SearchSuggest 组件，输入联想后点击直接添加到自选股，默认归入当前选中分组。
- **FR-6**: 列表排序——支持按涨跌幅/添加时间/名称拼音/自定义顺序 4 种排序方式切换，顶部下拉框选择。
- **FR-7**: 批量操作——表格首列加复选框，支持全选/反选、批量移除、批量改分组，操作需事务保证。
- **FR-8**: 列表内嵌 mini K 线——表格右侧加「30 日趋势」列，用 ECharts 渲染 30 日收盘价 sparkline 折线，涨红跌绿，悬停显示 tooltip。
- **FR-9**: 列表显示列扩展——新增行业/总市值/PE(TTM)/PB/换手率 5 列，数据来自 daily_basic + sw_industry，格式化显示，空值降级为「-」。
- **FR-10**: 前端脚本独立化——将内联在 `watchlist.html` 的脚本抽出为独立 `static/js/watchlist.js` 文件，IIFE 封装，暴露 `WatchlistPage` 全局对象。

## Non-Functional Requirements
- **NFR-1 性能**：首屏加载（含 50 条 mini K 线）≤ 2 秒；翻页响应 ≤ 500ms；50 个 mini K 线图首屏渲染 ≤ 1 秒。
- **NFR-2 价格提醒实时性**：价格触达阈值后 60 秒内触发提醒；同一股票同一阈值 5 分钟内只提醒一次。
- **NFR-3 内存管理**：每个 ECharts mini 实例内存 ≤ 100KB；翻页/切换分组时旧实例必须 dispose，无内存泄漏。
- **NFR-4 数据降级**：daily_basic 无数据时 5 列显示「-」；daily_quote 无数据时 mini K 线显示「暂无数据」；sw_industry_member 无数据时隐藏行业分组；个股诊断未上线时代码列降级为纯文本。
- **NFR-5 主题统一**：三主题（azure/mist/cyber）切换后，列表颜色 + mini K 线颜色 + 分组栏样式同步变化，无硬编码颜色。
- **NFR-6 兼容性**：Chrome/Edge/Firefox 最新版；最低分辨率 1366×768；不兼容 IE。
- **NFR-7 schema 双库同步**：所有表结构变更同步更新 MySQL + SQLite 两份 schema + 注释文件，字段类型对齐。
- **NFR-8 向后兼容**：扩展字段均允许 NULL，已有自选股记录默认 NULL/0，现有接口不报错。

## Constraints
- **Technical**: Java 21 + Spring Boot 4.0.6 + MyBatis-Plus；Thymeleaf + Bootstrap 5 + ECharts 5；MySQL 8+ / SQLite 双部署；akquant 0.2.47 仅 engine 侧使用，本模块不涉及。
- **Business**: 数据单源性（自选股只写 sys_watchlist 自有表，不写入行情表）；不做实时盘中推送；不做移动端适配。
- **Dependencies**: 弱依赖个股诊断（017）路由、弱依赖板块行情（015）行业列表接口、强依赖 SearchSuggest 组件 + 联想接口、依赖 daily_quote/daily_basic/stock_basic/sw_industry 数据。

## Assumptions
- 用户已登录，自选股按 user_id 隔离。
- daily_quote / daily_basic / stock_basic / sw_industry / sw_industry_member 表已有数据，由 watcher 定时任务采集。
- SearchSuggest 组件（`search-suggest.js`）及后端联想接口 `/search/suggest` 已可用。
- 价格提醒基于收盘数据，非实时盘中价，用户理解数据延迟。
- 个股诊断模块（017）后续会上线，跳转链接预留，未上线时做降级处理。

## Acceptance Criteria

### AC-1: 表结构扩展与新建分组表
- **Given**: 数据库已初始化
- **When**: 执行 schema-mysql.sql 和 schema-sqlite.sql
- **Then**: `sys_watchlist` 表包含 group_id/note/target_price_high/target_price_low/sort_order 5 个新字段，均允许 NULL；`idx_sys_watchlist_group` 索引存在；`sys_watchlist_group` 表存在且字段完整（id/user_id/group_name/created_at/sort_order）；schema-mysql-comments.sql 包含对应注释。
- **Verification**: `programmatic`
- **Notes**: 验证 MySQL DESC sys_watchlist / SQLite PRAGMA table_info / SHOW INDEX

### AC-2: 价格提醒设置与触发
- **Given**: 用户已登录，自选股列表有股票
- **When**: 点击某行「设提醒」按钮，设置目标价上限 15 元并保存
- **Then**: 列表该行显示小铃铛图标；60 秒内轮询比对最新价，若最新价 ≥ 15 元则顶部弹出通知条 + toast 提醒 + 行高亮闪烁；通知条显示股票名+代码+当前价+触发条件；点击通知条跳转个股诊断页；5 分钟内同一阈值重复触发只提醒一次。
- **Verification**: `programmatic`
- **Notes**: 同时验证涨跌幅模式、清除提醒、后台 tab 降频

### AC-3: 分组管理与拖拽排序
- **Given**: 用户已登录，有若干自选股
- **When**: 点击「+ 新建分组」输入「新能源」并创建
- **Then**: 左侧分组栏显示新分组；在该分组下用搜索添加新股票，新股票自动归入该分组；表格行可拖拽调整顺序，刷新后保持；行可拖拽到左侧分组名上跨分组移动；分组右键可重命名/删除，删除后组内股票移至未分组；行业分组按申万一级行业自动归类正确。
- **Verification**: `programmatic`
- **Notes**: 验证行业分组降级（sw_industry_member 无数据时不显示行业分组）

### AC-4: 股票代码跳转个股诊断
- **Given**: 用户已登录，自选股列表有股票
- **When**: 点击列表中「600036.SH」代码链接
- **Then**: 跳转到 `/page/stock-detail/600036.SH` 页面；个股诊断未上线时显示为纯文本不可点击，或点击后 404 页提示「即将上线」。
- **Verification**: `human-judgment`
- **Notes**: 降级方案在实现阶段确定

### AC-5: SearchSuggest 搜索添加
- **Given**: 用户已登录，在自选股页面
- **When**: 顶部搜索框输入「zsyh」，点击联想结果「招商银行 600036.SH」
- **Then**: toast 提示「已添加招商银行到自选股」；列表刷新显示招商银行；若当前选中某自定义分组则新股票归入该分组；重复添加时 toast 提示「已在自选股中」不重复添加。
- **Verification**: `programmatic`
- **Notes**: 验证拼音联想、中文联想、空结果提示

### AC-6: 列表多维排序
- **Given**: 用户已登录，自选股列表有多只股票
- **When**: 顶部排序下拉选择「按涨跌幅降序」
- **Then**: 列表按 pct_chg 从高到低排列；切换「按添加时间」按 createdAt 降序；切换「按字母」按名称拼音升序；切换「按自定义顺序」按 sort_order 升序且行可拖拽。
- **Verification**: `programmatic`

### AC-7: 批量操作
- **Given**: 用户已登录，自选股列表有多只股票
- **When**: 勾选 3 只股票，点击「批量移除」，二次确认
- **Then**: 3 只股票从列表消失；toast 提示「已移除 3 只自选股」；批量操作有事务保证，中途失败全部回滚；批量改分组同理生效。
- **Verification**: `programmatic`
- **Notes**: 验证全选/反选、未勾选时按钮隐藏

### AC-8: mini K 线渲染与性能
- **Given**: 用户已登录，自选股列表有 50 只股票
- **When**: 页面加载完成
- **Then**: 表格右侧「30 日趋势」列每行显示 sparkline 折线；30 日整体涨的折线为红色+红色填充，跌的为绿色+绿色填充；鼠标悬停显示 tooltip（起止价/最高/最低/涨跌幅）；50 条 mini K 线首屏渲染时间 ≤ 1 秒；翻页后旧实例已 dispose 无内存泄漏。
- **Verification**: `programmatic`
- **Notes**: 验证新股不足 30 日的降级显示

### AC-9: 列表列扩展与格式化
- **Given**: 用户已登录，自选股列表有股票且 daily_basic 有数据
- **When**: 页面加载完成
- **Then**: 列表显示行业/总市值/PE(TTM)/PB/换手率 5 列；总市值显示为亿元（除以 10000，保留 2 位小数）；PE/PB 负值或 NULL 显示「-」；换手率显示为百分比；行业显示申万一级中文名。
- **Verification**: `programmatic`
- **Notes**: 验证 daily_basic 无数据时 5 列显示「-」的降级

### AC-10: 前端脚本独立化与功能无回归
- **Given**: 自选股页面 W1-W9 功能已实现
- **When**: 访问 `/page/watchlist` 页面
- **Then**: `static/js/watchlist.js` 文件存在且被页面正确引入；HTML 无内联 `<script>` 业务逻辑块；W1-W9 所有功能正常工作无回归；common.js/search-suggest.js/echarts 加载顺序正确；二次访问时 watchlist.js 走浏览器缓存。
- **Verification**: `programmatic`

### AC-11: 整体体验与主题兼容
- **Given**: 自选股页面所有功能已实现
- **When**: 切换三主题（azure/mist/cyber）
- **Then**: 列表颜色 + mini K 线颜色 + 分组栏样式同步变化，布局不乱；首屏加载 ≤ 2 秒；端到端流程（加入→分组→设提醒→触发→跳转）顺畅无报错；列表顶部显示数据日期。
- **Verification**: `human-judgment`

## Open Questions
- [ ] 股票代码跳转的降级方案：个股诊断（017）未上线时，是渲染为纯文本不可点击，还是点击后 404 页提示「即将上线」？
- [ ] 价格提醒是否需要持久化到数据库的提醒历史表，还是仅前端 localStorage 记录？
- [ ] 分组排序是否需要支持拖拽调整分组栏中分组的显示顺序？
- [ ] 自选股列表是否需要分页，还是一次性加载全部（预期 ≤ 200 只）？
- [ ] 备注（note）字段是否需要一期实现编辑功能，还是仅扩展字段预留？
