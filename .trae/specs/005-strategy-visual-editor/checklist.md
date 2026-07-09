# Checklist

## 条件树构建器组件（Task 1）
- [x] 可复用条件树组件已从 screener.js 抽取为独立文件（condition-tree-builder.js），不再耦合 screener 全局 state
- [x] 组件提供清晰的 mount/onChange API，外部传入 tree 初始值与白名单，变更通过回调上抛（深拷贝）
- [x] 组件支持约束开关（allowRef / allowCross），分别服务于 screen（禁）与 trading（允）
- [x] 因子选择器从白名单加载，多输出因子（MACD/BOLL/KDJ）强制选 output_index
- [x] 配套 CSS 已抽取（ctb- 前缀），与 strategy.css / screener.css 无命名冲突
- [x] 组件支持策略 Schema 4 形态归一化（ValueNode/FactorNode/OpNode/RefNode），node --check 通过

## 录入模式切换（Task 2）
- [x] 选股范围 / 买入信号 / 卖出信号 / 出场规则 四处均有「可视化 / JSON / AI」模式切换器
- [x] 默认进入可视化模式（空树显示空态 + 添加条件按钮）
- [x] AI 模式为占位提示（按钮 disabled），不提供输入，不改 state
- [x] 可视化 → JSON：编辑实时同步 state 与右侧 JSON 预览（_syncing 防循环）
- [x] JSON → 可视化：切换/失焦时 JSON.parse，成功更新 state 渲染树，失败提示并阻止切换、state 不变

## 因子白名单（Task 3）
- [x] 因子白名单含 displayName / multiOutput / outputLabels，足以驱动可视化选择器
- [x] screen 与 trading 上下文按规则区分可用因子/比较器（screen 禁 cross_*/ref；trading 允）

## 跳转联动（Task 4）
- [x] 选股中心方案有「作为策略选股范围」按钮，跳转带 screenSource 参数
- [x] 策略编辑器 init 读取 screenSource，拉取方案并注入 state.screen_config
- [x] 注入后选股范围 Tab 显示「已从选股方案导入」提示（toast）
- [x] 仅填充 screen_config，trading/backtest 保持默认
- [x] planId 无效/拉取失败时优雅回退，不阻塞编辑器

## 校验与互通（Task 5）
- [x] 三模式产出的 JSON 走同一 engine 校验链路，口径一致
- [x] engine 校验错误仍能按 Tab 定位标红，可视化模式下错误至少定位到 Tab
- [x] node --check 语法校验全部通过（condition-tree-builder.js / strategy-editor.js / screener.js）
- [x] 链路完整：可视化建树→切JSON→改JSON→切回可视化→保存校验；选股方案跳转导入→编辑→保存

## 范围约束
- [x] engine 侧（Python）无任何代码改动
- [x] 持久化结构（quant_strategy / quant_strategy_version / config_json）无改动
- [x] 现有 JSON 模式行为向后兼容（原有 textarea / 插入因子下拉 / 预校验全部保留）
- [x] AI 模式未接入任何后端，仅前端占位
