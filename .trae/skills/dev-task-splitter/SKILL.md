---
name: "dev-task-splitter"
description: "根据 doc/design 下的研发方案文档，将方案拆分为后端/前端/Python 计算引擎三层有序的可追踪任务清单，每个任务独立文件夹存放详细描述，以 state.json 记录状态，支持增量合并。Invoke when user asks to split design docs into dev tasks or check task progress."
parameters:
  - name: "module-query"
    description: "（可选）设计模块名称或编号关键字，如 "k线"、"002"。如不提供则自动扫描 doc/design/ 下所有目录，单目录直接选中，多目录让用户选择。"
    required: false
---

# 研发任务拆分器

> 读取 `doc/design/` 下某个设计模块的方案文档，拆分为后端(Java)/前端/Python计算引擎三层有序的可追踪任务清单，每个任务以独立文件夹存放在 `doc/task/方案名/` 下，以 `state.json` 记录状态，支持增量合并。

## 0. 执行入口

**调用方式**：直接携带关键字（或不携带任何参数），不需要 `--module-query` 前缀。

```
dev-task-splitter              # 不携带参数 → 扫描 doc/design/ 所有目录
dev-task-splitter 001          # 携带编号 → 按编号匹配
dev-task-splitter k线           # 携带名称 → 按名称模糊匹配
```

**匹配流程**：
1. 扫描 `doc/design/` 下所有子目录
2. **不携带参数**：1 个目录 → 自动选中；多个 → 列出供用户选择
3. **携带参数**：大小写不敏感模糊匹配目录名和文件名。1 个 → 自动选中；≥2 个 → 列出供选择；0 个 → 提示无匹配并列出所有目录

## 1. 读取与解析

1. 按 §0 匹配流程确定目标模块
2. **渐进式阅读设计文档**：先读目录结构和章节标题建立全局视图，再按需深入功能章节。理解：DO/DTO/VO、Service/Controller/Mapper/Config/Task/Util、前端 templates/pages + static/js、Python 侧 FactorRegistrar/RuleParser/data_collector/indicator/backtest/api.v1/models.schemas/utils/core 及依赖关系
3. **按需扫描现有代码库**：用 SearchCodebase/Grep 按需检索，了解当前实现状态，避免列出已存在且无需修改的文件

## 2. 核心规则（硬性约束，必须遵守）

### 2.1 Phase 划分（交付里程碑）

| Phase | 目标 | 规模 |
|-------|------|------|
| **phase1** — MVP 最小闭环 | 选 1-2 个最核心功能点，打通 BE→PY→FE 完整链路，**完成后可独立验证** | 3-5 个任务 |
| **phase2** — 主体扩展 | 实现设计文档剩余主体功能 | 4-8 个任务 |
| **phase3** — 完善优化 | 缓存/性能/高级UI/异常处理，**不强制有 PY 任务** | 2-5 个任务 |

### 2.2 拆分优先级（硬性顺序，不可颠倒）

1. **按设计章节自然边界**：一个设计章节 → 一个或多个任务，**禁止跨章节合并**（章节内容极少除外）
2. **按量化上限二次拆分**：章节超标时，在章节内部按 BE/FE/PY 分层或按子功能进一步拆分
3. **按 BE/FE/PY 分层**：同一章节涉及多层技术栈时，每层拆独立任务，通过 `depends_on` 声明跨层依赖

### 2.3 量化上限（超标必须拆分）

- 单个任务文件数 **≤ 5-8 个**
- 单个任务新增代码量预估 **≤ 200-300 行**
- 单个任务改动要点 **≤ 5 步**
- 每 phase 任务数 **≤ 6-8 个**（超标则拆为 phase2a/phase2b）

### 2.4 文件所有权（防止冲突）

- files 必须区分 `create:` / `modify:` 前缀
- **同一文件禁止被两个任务同时声明"本任务首次创建"**
- 任务文件中 files 表格必须包含「所有权」列（"本任务首次创建" / "本任务只做修改"）

### 2.5 设计引用（防止功能丢失）

- 每个任务 **必须有 design_ref**，关联原设计文档的具体章节
- 拆分完成后，所有任务 `design_ref.sections` 的并集 **必须覆盖设计文档的所有功能章节**
- 存在未覆盖章节即违规

### 2.6 依赖规则

- `depends_on` 仅使用 seq 编号（如 `"01"`, `"03"`），无需 phase 前缀
- **引用编号必须严格小于当前任务编号**，保证 DAG 无环

### 2.7 反例（不要做什么）

- **不要**把一整章塞进一个大任务跳过拆分步骤
- **不要**在 phase1 放超过 5 个任务，或让 phase1 无法独立验证
- **不要**让两个任务的 design_ref 指向同一章节却不做所有权区分
- **不要**强行在 phase3 创建无意义的 PY 占位任务
- **不要**覆盖 status=done 的已有任务

## 3. 任务字段定义

| 字段 | 说明 |
|------|------|
| `phase` | `phase1` / `phase2` / `phase3` |
| `seq` | 两位数字，全模块连续递增（01→09...），编号永不重置 |
| `layer` | `backend` / `frontend` / `python`（一个任务只落一个 layer） |
| `module` | 所属设计模块名 |
| `title` | 格式「后端-xxx」/「前端-xxx」/「Python-xxx」 |
| `description` | 具体要做什么 |
| `files` | `create:path` 或 `modify:path`，见 §2.4 |
| `design_ref` | 设计文档路径 + 章节号，见 §2.5 |
| `depends_on` | 前驱任务编号列表，见 §2.6 |
| `verification` | 2-5 条可验证标准（可执行、可验证的具体动作） |
| `status` | `pending` / `in_progress` / `done` |
| `notes` | 备注（实现约束/注意事项） |

## 4. 产出文件结构

```
doc/task/
  {方案名}/
    state.json              ← 模块级状态文件
    phase1/
      task-01-be.md         ← 文件名含 layer 缩写：be/fe/py
      task-02-fe.md
      task-03-py.md
    phase2/
      task-04-be.md         ← 编号跨 phase 连续递增
      ...
    phase3/
      ...
```

- phase 目录名：`phase1` / `phase2` / `phase3`（超标可扩展 phase2a/phase2b）
- 任务文件名：`task-NN-{layer}.md`，NN 全模块从 01 连续递增不重置

### 4.1 任务文件格式模板

```markdown
# [{phase}] task-{seq}-{layer} - {layer中文}-{标题}

## 基本信息
- **模块**: {模块名}
- **阶段**: {phase}
- **层级**: {layer}
- **依赖任务**: {depends_on 描述，无则写"无"}
- **可独立验证**: {是/否 + 简述原因}

## 设计引用
- **设计文档**: `{路径}`
  - 章节: {§章节号 + 标题}
- **关键引用片段**: "{原文摘录}"（摘自 {文件名} {§章节}）

## 任务描述
{2-3 句话，说明目标 + 在 phase 中的定位}

## 改动要点（≤5 步）
1. {新建/修改什么，做什么，方法签名，输入输出}
2. ...

## 可验证标准（2-5 条）
- [ ] {可执行、可验证的具体动作}
- [ ] ...

## 涉及文件
| 文件 | 操作 | 所有权 | 说明 |
|------|------|--------|------|
| `{路径}` | 新建/修改 | 本任务首次创建/只做修改 | {说明} |

## 实现约束
- {前置依赖、输入输出约定、规范}
```

### 4.2 精简示例（Python 任务，展示跨层依赖）

```markdown
# [phase1] task-03-py - Python-因子注册与 MA 因子计算

## 基本信息
- **模块**: 002-量化策略引擎V2.0
- **阶段**: phase1
- **层级**: python
- **依赖任务**: task-01-be（后端数据模型）
- **可独立验证**: 是（REST API 调用即可验证）

## 设计引用
- **设计文档**: `doc/design/002-量化策略引擎V2.0/01-main-design.md`
  - 章节: §3.2 Python 计算引擎模块、§4 因子库设计
- **关键引用片段**: "Python 侧 FactorRegistrar 负责因子注册与计算，Java 侧通过 ComputeGateway REST API 调用"（§3.2）

## 任务描述
实现 Python 侧基础因子注册系统，phase1 仅实现 MA 因子，打通 FactorRegistrar → REST API → Java ComputeGateway 完整链路。

## 改动要点
1. 新建 `FactorRegistrar` 类（`stock-engine/services/factor/registrar.py`），实现 `register_factor()` / `calculate_factor()` / `list_factors()`
2. 定义 REST API（`stock-engine/api/v1/factor.py`）：`POST /calculate`、`GET /list`、`GET /health`
3. 定义 Pydantic Schema（`stock-engine/models/schemas/factor.py`）
4. 在 `main.py` 注册 factor API 路由

## 可验证标准
- [ ] `GET /api/v1/factor/health` 返回 200 OK
- [ ] `POST /api/v1/factor/calculate` 对 MA_5 返回正确移动平均值
- [ ] `GET /api/v1/factor/list` 返回包含 MA 因子的元数据

## 涉及文件
| 文件 | 操作 | 所有权 | 说明 |
|------|------|--------|------|
| `stock-engine/services/factor/registrar.py` | 新建 | 本任务首次创建 | FactorRegistrar 核心类 |
| `stock-engine/api/v1/factor.py` | 新建 | 本任务首次创建 | 因子 REST API |
| `stock-engine/models/schemas/factor.py` | 新建 | 本任务首次创建 | Pydantic Schema |
| `stock-engine/main.py` | 修改 | 本任务只做修改 | 注册路由 |

## 实现约束
- 不直接连接 SQLite，数据通过 Java 侧 REST API 获取
- 遵循项目 `.claude/rules/` 命名规范
```

### 4.3 state.json 格式

```json
{
  "schema_version": "1.0",
  "module": "002-量化策略引擎V2.0",
  "generated_at": "2026-06-19T14:00:00",
  "last_updated_at": "2026-06-19T14:00:00",
  "tasks": {
    "phase1": [
      {
        "seq": "01",
        "phase": "phase1",
        "title": "后端-数据模型与基础 Mapper",
        "layer": "backend",
        "depends_on": [],
        "verification": ["FactorDO 字段与 db-design.md §2 一致", "单元测试通过"],
        "status": "pending",
        "files": ["create:stock-watcher/.../FactorDO.java", "create:stock-watcher/.../FactorMapper.java"],
        "design_ref": { "file": "doc/design/002-.../02-db-design.md", "sections": ["§2"] }
      }
    ],
    "phase2": [ ... ],
    "phase3": [ ... ]
  },
  "stats": {
    "total": 9, "done": 0, "pending": 9, "in_progress": 0,
    "by_phase": { "phase1": {"total":3, "backend":1, "frontend":1, "python":1}, ... },
    "by_layer": { "backend": 4, "python": 2, "frontend": 3 }
  },
  "validation": {
    "file_ownership_ok": true,
    "ownership_conflicts": [],
    "design_coverage_ok": false,
    "dependency_integrity_ok": true,
    "file_count_limits_ok": true,
    "file_count_violations": []
  },
  "design_coverage": {
    "covered_files": ["doc/design/002-.../01-main-design.md", "doc/design/002-.../02-db-design.md"],
    "uncovered_sections": ["doc/design/002-.../04-http-api.md: 未被任何任务引用"]
  }
}
```

**关键字段说明**：
- `tasks` 按 phase 分组，每个任务保留轻量状态（详细描述在对应 md 文件中）
- `stats` 每次保存前必须重新计算，包含 `by_phase` 和 `by_layer` 两个维度
- `validation` 为三重边界校验结果（见 §5）
- `design_coverage` 记录覆盖检查摘要
- `files` 路径使用项目相对路径，每项带 `create:` / `modify:` 前缀

## 5. 三重边界校验（拆分完成后必须执行）

| 校验项 | 规则 | 失败处理 |
|--------|------|----------|
| **文件所有权互斥** | 同一文件不能被两个任务同时 `create:` | 后声明者改为 `modify:` |
| **设计覆盖完整性** | 所有功能章节必须被 design_ref 覆盖 | 补充任务或添加 design_ref |
| **依赖完整性** | depends_on 引用编号必须存在且小于当前编号 | 调整顺序或修正引用 |

校验结果写入 `state.json` 的 `validation` 字段，输出中显式展示 ✓/✗。任一校验失败 → 自动尝试修复，修复仍失败则暂停让用户介入。

## 6. 执行流程

1. 扫描 `doc/design/` 识别设计模块，按 §0 匹配
2. 渐进式阅读设计文档，提取章节结构
3. 按需扫描现有代码库，了解已实现状态
4. **按设计章节 → 候选任务**（第一优先级：章节自然边界，禁止跨章节合并）
5. **按交付里程碑分配 phase**（phase1 MVP → phase2 主体 → phase3 优化）
6. **同一 phase 内按 BE/FE/PY 二次拆分**，按依赖顺序排序：数据层 → 业务层 → 接口层 → 前端 → 计算引擎
7. 设置 `depends_on`（编号必须递增）
8. 为每个任务生成 verification（2-5 条，可执行可验证）
9. 填充 `design_ref` + files 操作类型和所有权
10. 执行三重边界校验（§5），结果写入 state.json
11. 创建目录结构，写入任务文件和 state.json
12. 输出任务清单 + validation 校验 + 设计覆盖检查表

## 7. 增量合并

若 `doc/task/方案名/` 已存在：

| 场景 | 处理 |
|------|------|
| 任务 status=done | 任务文件和 state.json 条目保持不动 |
| 任务 status=in_progress/pending 且设计文档有变更 | 更新任务内容，保留 status |
| 设计文档新增内容 | 新建任务文件，seq = 当前最大 + 1，编号永不重置 |
| 设计文档删除内容 | 不删除任务，标注 `notes: "设计文档已移除此需求，可跳过"` |
| 需新增 phase | 在 tasks 对象中新增 key |

合并后重新执行三重校验、重算 stats、更新 `last_updated_at`。

## 8. 异常处理

| 异常场景 | 处理策略 |
|----------|----------|
| 无设计文档 | 终止，提示用户先创建 |
| 设计文档缺失关键字段 | 提示补充，不自行假设，notes 中标注 |
| 设计文档无章节编号 | 按语义识别章节边界，提示"已按语义拆分" |
| 三重校验自动修复失败 | 暂停，列出违规项让用户决定 |
| 代码库扫描失败 | 降级为仅基于设计文档拆分，notes 中标注 |
| 已完成任务与设计文档严重不一致 | 不修改已完成任务，标注警告 |

## 9. 关键注意事项

1. **不覆盖已完成任务**：status=done 的任务保持不动
2. **seq 编号永不重置**：新任务 seq = 当前最大 + 1
3. **遵循项目代码规范**：阅读 `.claude/rules/`，Python 遵循 FastAPI + Pydantic 规范
4. **不自动提交 git**：本 skill 只负责拆分与状态记录
5. **stats 必须与实际一致**：每次保存前重新计算
6. **本 skill 只负责任务拆分**：不负责执行任务代码编写
7. **任务文件标题必须包含 phase**：如 `# [phase1] task-01-be - ...`
8. **同一设计章节关联多个任务时**：必须在 files 所有权列区分"首次创建"和"只做修改"
9. **增量合并时同步更新 design_ref**：设计文档更新后检查已有任务的引用是否仍有效
10. **Python 模块独立部署**：涉及 Python 服务的任务需在约束中说明不直接连接 SQLite

## 10. 调用示例

- 「帮我拆分 K线 模块的开发任务」→ 匹配 001-K线模块，生成任务 + state.json + 输出清单
- 「帮我拆分开发任务」（不传参数）→ 列出 doc/design 下所有模块供选择
- 「还有哪些任务没做完」→ 读取 state.json，输出统计和待完成清单

**输出格式**：

```
==================================================
 模块: 002-量化策略引擎V2.0
 总任务: 9  |  已完成: 0  |  待完成: 9
 按 phase: phase1(3) → phase2(4) → phase3(2)
 按层: BE=4  PY=2  FE=3
 边界校验: ✓所有权  ✗覆盖(1章未覆盖)  ✓依赖  ✓规模
==================================================

Phase 1 — MVP 最小闭环
  [task-01-be] 后端-数据模型与基础 Mapper → §2  依赖:无  pending
  [task-02-fe] 前端-因子库页面骨架       → §6  依赖:01  pending
  [task-03-py] Python-因子注册与MA计算    → §3.2,§4  依赖:01  pending

Phase 2 — 主体扩展
  [task-04-be] 后端-ComputeGateway → §3.1  依赖:01,03  pending
  ...

⚠️ 未覆盖: doc/design/002-.../04-http-api.md
==================================================
```
