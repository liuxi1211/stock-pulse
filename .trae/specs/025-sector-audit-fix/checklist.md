# Checklist

> 对照 spec.md �?Requirements 逐项验证�?> 验证方式：编译通过 + API 端点 200 响应 + 代码审查 + 页面功能验证 + 回归测试

## 阶段一：后端正确性与性能基线（P0/P1�?
- [x] `DailyQuoteMapper.xml` �?`selectMembersWithQuote` 使用 `ORDER BY (q.pct_chg IS NULL), q.pct_chg DESC` -- XML 审查
- [x] 成分股分页跨页不再错位（停牌股沉底，分页稳定�?-- 代码审查 + 数据验证
- [x] `CacheConfig` 注册 `sectorRanking` 缓存空间（TTL 合理�?-- 代码审查
- [x] `CacheConfig` 注册 `stockBasicName` 缓存空间 -- 代码审查
- [x] `SwIndustryServiceImpl.getIndustryRanking` 标注 `@Cacheable("sectorRanking")` -- 代码审查
- [x] `buildStockNameMap` 改为全量 `selectList(null)` + 缓存，不再用超长 IN -- 代码审查
- [x] 数据同步任务（IndexDaily/Moneyflow/DailyQuote）触�?`sectorRanking` 缓存淘汰 -- 代码审查
- [x] ranking 二次请求命中缓存（不�?DB，响�?< 50ms�?-- 性能验证
- [x] `/api/index-daily` tsCode 通过正则 `[A-Z0-9]{6,9}(\.(SH|SZ|SI))?` 校验 -- 代码审查
- [x] `/api/index-daily` limit 校验 1~3650，超界返�?400 -- 代码审查 + 接口验证
- [x] `IndexDailyController` 反转结果无副作用（`new ArrayList<>()` �?Service �?ASC�?-- 代码审查
- [x] `/api/industry/members` 新增 `tradeDate` 参数（`\d{8}` 校验）并透传 Service -- 代码审查
- [x] `?tradeDate=20240115` 返回历史交易日成分股行情 -- 接口验证

## 阶段二：后端功能补齐（缺�?1/2/3/4�?
- [x] `constant/MarketThresholdConstants.java` 存在（主�?.9/创科19.9/北交29.9/ST4.9�?-- 代码审查
- [x] `MarketServiceImpl.getMarketTemperature` 改用 `MarketThresholdConstants`（回归不变） -- 代码审查 + 回归
- [x] `IndustryRankingVO` �?`upCount/downCount/limitUpCount/limitDownCount` -- 代码审查
- [x] 家数统计�?6 步聚合遍�?memberQuotes 时完成，阈值来自公共常�?-- 代码审查
- [x] `upCount+downCount+平盘 = activeCount`（数量逻辑自洽�?-- 数据验证
- [x] `IndustryRankingVO` �?`pctChg5d/pctChg20d` -- 代码审查
- [x] N 日涨跌幅�?index_daily 历史 close 计算，数值正�?-- 数据验证
- [x] `IndustryMoneyflowVO` 新建（主�?超大�?大单/中单/小单净额字段） -- 代码审查
- [x] `GET /api/industry/moneyflow` 端点存在，返�?28 条聚�?-- 接口验证
- [x] 资金流由 `stock_moneyflow` JOIN `sw_industry_member` �?index_code 聚合，无新表/�?Tushare -- 代码审查
- [x] `IndustryValuationVO` 新建（peTtm/pb 等） -- 代码审查
- [x] `GET /api/industry/valuation` 端点存在，PE 市值加权正�?-- 接口验证 + 数据验证
- [x] moneyflow/valuation 接口启用 Caffeine 缓存 -- 代码审查

## 阶段三：前端交互修复（P1/P2�?
- [x] 刷新按钮点击�?K线图 + 成分股同步刷新（非仅排行�?-- 页面验证
- [x] 成分股到达最后一页时"下一�?禁用 -- 页面验证
- [x] 排行表表头排序图标随 sortState 动态变化（up/down/down-up�?-- 页面验证
- [x] 热度�?Tooltip 接近视口�?下边缘时不溢出（无横向滚动条�?-- 页面验证
- [x] 成分股成交额格式与排行榜一致（亿元，非成交量格式） -- 页面验证
- [x] K�?全部"范围拉取 3650 条（�?9999�?-- 网络请求验证
- [x] 成分股搜索按钮可触发搜索（非死按钮） -- 页面验证
- [x] 排行表行点击改为 addEventListener（移除或兼容 onclick 内联�?-- 代码审查

## 阶段四：前端功能展示

- [x] 排行表展示家�?资金�?趋势新列（或卡片�?-- 页面验证
- [x] 热度�?Tooltip 含家数、资金流数据 -- 页面验证
- [x] 新列红涨绿跌颜色正确 -- 页面验证
- [x] 个股详情页含"所属板�?标签，点击跳板块�?-- 页面验证
- [x] 板块页支持从 URL 参数预选行业（`?industryCode=`�?-- 页面验证

## 阶段五：联调验证

- [x] 编译通过：`node stock-watcher/run.js compile-dev` BUILD SUCCESS -- 命令验证
- [x] 回归�?17 市场温度功能正常（阈值常量抽取后�?-- 回归验证
- [x] 回归�?16 仪表盘板块概览卡片正常（ranking 返回格式兼容�?-- 回归验证
- [x] 回归�?19 板块行情原有功能（热度图/排行/K�?成分股）不破�?-- 回归验证
- [x] ranking 接口性能：二次请�?< 50ms（缓存命中） -- 性能验证

## 明确不做（排除项�?
- [x] 概念板块（缺�?）未处理 -- 确认排除（无表无对接，v2 规划�?