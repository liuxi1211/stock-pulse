# Tasks

- [x] Task 1: 抽取可复用条件树构建器组件（condition-tree-builder.js + css）
  - [x] 1.1: 从 `static/js/screener.js` 抽取 ConditionTree 构建逻辑（renderNode / addLeaf / addGroup / removeNode / normalizeTree / 校验），封装为独立组件，不依赖 screener 的全局 state
  - [x] 1.2: 组件 API 设计：`mount(container, { tree, onChange, allowRef, allowCross, factors })`，内部维护树副本，变更通过 onChange 回调上抛；支持只读约束（screen 禁 cross_*/ref，trading 允许）
  - [x] 1.3: 因子选择器：从外部传入 factors 白名单（factorKey + displayName + multiOutput/outputLabels），支持多输出因子选 output_index
  - [x] 1.4: 配套 CSS（从 screener.css 抽取条件树相关样式，去重命名冲突）
  - [x] 1.5: 回归验证：组件支持策略 Schema 4 形态（ValueNode/FactorNode/OpNode/RefNode），node --check 通过（注：选股中心暂保留原实现，未强制替换，作为后续优化）

- [x] Task 2: 策略编辑器录入模式切换器（editor.html + strategy-editor.js）
  - [x] 2.1: 在含条件树的 4 个区域（screen conditions / buy / sell / exit.rules）顶部增加模式切换器：可视化 / JSON / AI（敬请期待）
  - [x] 2.2: 实现模式切换逻辑：可视化↔JSON 互转（共享同一 state 路径），AI 模式仅占位提示
  - [x] 2.3: 可视化模式挂载 Task 1 组件，绑定到 state 对应路径；JSON 模式保留原 textarea + 插入因子下拉
  - [x] 2.4: 双向同步：可视化编辑 → 实时更新 state + 右侧 JSON 预览；JSON textarea 切换/失焦 → 解析注入 state（失败则提示并阻止切换）
  - [x] 2.5: exit.rules 是列表，每条 rule 有独立条件树（用 uid 寻址）；���视化模式支持多 rule 条件树渲染与增删 rule

- [x] Task 3: 因子白名单加载与传递
  - [x] 3.1: ConditionTreeBuilder.loadFactors() 调 /factors 与 /factors/categories，返回 {factors, categories} 含 displayName/multiOutput/outputLabels
  - [x] 3.2: 按 screen vs trading 区分可用因子集（screen 禁 cross_*/ref，技术面+基本面均可；trading 允许 cross_* 与 ref）

- [x] Task 4: 因子库 / 选股中心 → 策略编辑器跳转联动
  - [x] 4.1: 选股中心（screener.html / screener.js）：方案详情/保存成功后增加「作为策略选股范围���按钮，跳转 `/quant/strategies/new?screenSource=<planId>`
  - [x] 4.2: 因子库（factor-library.html）：页头增加「配置选股方案」引导链接指向选股中心
  - [x] 4.3: 策略编辑器 init：读取 `?screenSource=`，调 `GET /screener/plans/{id}` 拉取 screen_config，注入 state.screen_config
  - [x] 4.4: 注入后在选股范围 Tab 显示「已从选股方案导入」提示（toast）
  - [x] 4.5: 边界处理：planId 不存在/拉取失败时提示错误并回退到默认空 state，不阻塞编辑器使用

- [x] Task 5: 端到端校验与错误定位回归
  - [x] 5.1: 三模式产出的 JSON 均走现有前端预校验（JSON.parse + 字段）+ engine `POST /python/v1/strategies/validate`，确保口径一致
  - [x] 5.2: engine 校验错误按 ERROR_TAB_MAP 定位 Tab 标红的逻辑，扩展到可视化模式（错误 path 能映射回可视化节点高亮，至少映射到 Tab）
  - [x] 5.3: 手工回归用例验证（静态检查 + node --check 语法校验全部通过）

# Task Dependencies
- Task 2 依赖 Task 1（可视化模式需挂载条件树组件）
- Task 3 为 Task 1/2 提供因子元数据输入，可与 Task 1 并行但 Task 2 需等其完成
- Task 4 独立于 Task 1-3（跳转 + 注入逻辑不依赖可视化模式，JSON 模式下也能体现导入），可并行
- Task 5 依赖 Task 1-4 全部完成
