---
name: "prd-design"
description: "根据需求 PRD + 原型图 编写研发设计方案（主设计+DB设计+SQL+HTTP接口）。Invoke when user asks to turn a PRD into development/technical design documents."
parameters:
  - name: "prd-query"
    description: "（可选）需求名称或编号关键字。如不提供则自动扫描 sdlc/prd/ 下所有目录，单目录直接选中，多目录让用户选择。"
    required: false
---

# PRD + 原型图 → 研发设计方案 生成 Skill

> 面向 Stock Watcher 项目：读取 `sdlc/prd/<需求目录>/` 下的 PRD 文档（+ 可选 `prototype/` 原型图），结合项目知识库，产出完整的研发设计文档。
> 设计文档中显式引用 PRD 和原型图路径，方便后续编码时回溯。

> ⚠️ **文档结构对齐当前仓库实况**（2026-07）：
> - **产出位置**：设计文档落在 `sdlc/prd/<需求目录>/`（旧 `sdlc/design/` 已并入 prd，不再使用）。
> - **HTML 原型**：项目已移除 `prototype/` 工作流；若 `<需求目录>/prototype/` 不存在，跳过原型相关步骤，模板中 prototype 引用一律按"若存在"处理。
> - **规则文件**：`.claude/rules/` → `.trae/rules/`（`general/`、`stock-watcher/`、`stock-engine/`、`akquant/`）。
> - **量化类模块**（选股/策略/回测）的字段与接口权威是 `sdlc/prd/004-策略管理/统一策略配置Schema.md`，不适用本 Skill 的 4-文档产出；本 Skill 面向其外的业务模块（信号/交易/复盘/系统等）。

---

## 1. 核心原则

### 1.1 调用方式
直接携带关键字（或不携带任何参数），不需要 `--prd-query` 前缀。

示例：
```
prd-design              # 不携带参数 → 扫描 sdlc/prd/ 所有目录
prd-design 003          # 携带编号 → 按编号匹配
prd-design 回测          # 携带名称 → 按名称模糊匹配
```

匹配流程：扫描 `sdlc/prd/` → 不携带参数时单目录直接选中、多目录列出选择；携带参数时模糊匹配目录名，匹配 1 个直接选中，0 个回退到列出所有目录。

### 1.2 复用优先
- **表**：`schema.sql` 中已存在的同名或语义等价表 → 直接复用，不重复定义；需扩展字段时写增量 `ALTER TABLE`
- **接口**：已存在同路径或可扩展的接口 → 标注复用 Controller，只写新增方法；绝不设计语义重复的新 URL
- **错误码**：优先复用 `ErrorCode.java` 中已有常量，禁止新增同义 code
- 最终的「复用 vs 新增」决策摘要写入主设计文档的独立小节「4.7 复用清单」

### 1.3 知识库加载（不写死文件名）
- 必选：读取 `CLAUDE.md`（项目总览 + 规则文件索引）
- 扫描：`Glob .trae/rules/**/*.md` 获取当前所有规则文件列表
- 按需：根据 PRD 核心主题加载匹配的规则文件（如涉及 Tushare → `07-*`，权限 → `06-*`，K线 → `09-*`）
- 无法判断时优先读取编号 01~10 范围内实际存在的文件
- 文件名变化/新增是正常的，以当前目录实际文件为准

### 1.4 代码基底扫描（实时扫描，禁止固定清单）
扫描以下文件以形成复用决策依据：
- `src/main/resources/schema.sql` → 现有表清单（表名 + 主键 + UNIQUE + 索引）
- `src/main/java/com/arthur/stock/controller/*Controller.java` → 现有接口清单（排除 `PageController`）
- `src/main/resources/application.yml` → Spring profile / datasource / scheduled / Tushare 配置
- `src/main/java/com/arthur/stock/dto/ApiResponse.java` → 统一返回结构
- `src/main/java/com/arthur/stock/exception/ErrorCode.java` → 现有错误码清单
- 任一 `*Mapper.java` + `*ServiceImpl.java` → 理解 MyBatis-Plus 继承模式和 upsert 模式

> **关键**：所有表/接口/错误码的复用决策，必须以本次实时扫描结果为准，不得使用 Skill 文档中的固定清单。

---

## 2. 产出物

在 `sdlc/prd/<需求目录>/` 下新建设计文档（设计已并入 prd，不再用 `sdlc/design/`）。

**文件版本策略（不覆盖已有文件）**：目标文件不存在 → 直接创建；已存在 → 使用 `-YYYYMMDD` 日期后缀新建版本；同日已有版本 → 追加 `-1` / `-2`。

**产出 4 份文档**：

| # | 文件名 | 内容要点 |
|---|--------|---------|
| 1 | `01-main-design.md` | 主设计文档（头部含「参考资料索引」引用 PRD + 原型图；含「原型图分析」章节） |
| 2 | `02-db-design.md`  | DB 设计文档（每个表的字段设计中标注原型图来源） |
| 3 | `03-schema.sql`     | 建表 SQL（只输出本次新增/变更，含设计依据注释） |
| 4 | `04-http-api.md`    | HTTP API 设计（每个接口标注对应原型图页面的操作） |

---

## 3. 文档模板

### 3.1 `01-main-design.md` 主设计文档

**头部固定格式**（显式引用设计依据）：

```markdown
# <需求名称> — 研发主设计方案

> 参考资料索引：
> - 需求 PRD：`sdlc/prd/<需求目录>/<PRD文件名>.md`
> - 验收文档（如有）：`sdlc/prd/<需求目录>/<验收文档名>.md`
> - 补充文档（如有）：`sdlc/prd/<需求目录>/<补充文档名>.md`
> - 原型图：
>   - `sdlc/prd/<需求目录>/prototype/<页面1>.html` — <页面标题/功能概述>
>   - ...（逐一列出所有原型图）
>
> 项目：Stock Watcher（Java Spring Boot + SQLite + Thymeleaf + ECharts）
> 匹配过程：用户输入「<原始参数>」→ 匹配到目录「<匹配到的目录名>」
```

**固定章节**（按顺序撰写，不得省略）：

```markdown
---

## 1. 需求摘要
1-2 段话总结 PRD 核心目标与本次设计范围。引用 PRD 中的功能清单/用户故事/优先级。

## 2. 原型图分析（页面结构与交互流程）
| 页面（原型图） | 核心功能 | 关键表单字段 | 主要操作 | 与其他页面的关联 |
|---------------|---------|-------------|---------|-----------------|
| `xxx.html` | 页面标题的功能概述 | field1 / field2 | 创建 / 保存 / 删除 | 跳转到 yyy.html |

## 3. 目标与非目标
| 项 | 说明 |
|---|------|
| 本次做什么 | 列出 3-6 条明确交付的功能，按 PRD 优先级 P0/P1/P2 分类标注，标注对应原型图页面 |
| 本次不做什么 | 列出本期不在范围内的功能，说明原因 |

## 4. 技术方案

### 4.1 总体架构
说明本需求落在现有架构哪一层（Web / Service / Mapper / Task / Cache 等），以及新增模块与现有模块之间的交互关系。

### 4.2 模块划分
| 模块 | 职责 | Java 包路径 | 主要类 | 对应原型图页面 |
|------|------|-------------|--------|---------------|

### 4.3 关键流程
用文字流程图或有序列表描述 1-2 个核心流程，标注与现有接口（TushareClient / Mapper / Service / @Scheduled / Cache）的调用点。
如涉及定时任务：写明 cron + 执行窗口 + 失败重试。如涉及缓存：写明 key 规则 + 过期策略 + 失效条件。

### 4.4 类设计 / 接口契约
列出核心接口的 Service 方法签名、DTO、VO，说明字段含义与约束。

### 4.5 与现有系统的集成点
是否新增定时任务/缓存/枚举/表/接口/Tushare 调用/权限变更？逐一声明。

### 4.6 异常与错误码
列出业务异常场景，标注是否复用现有 ErrorCode 或需新增。
优先复用：BAD_REQUEST(400) / UNAUTHORIZED(401) / FORBIDDEN(403) / NOT_FOUND(404) / CONFLICT(409) + 已定义业务码。
需新增的错误码，写明：新枚举常量名 + code 值 + message 文案。

### 4.7 复用清单（必须基于实时扫描结果）
| 类别 | 现有资源（来自实时扫描） | 复用决策 | 说明 |
|------|---------------------|---------|------|
| 表 | <逐一列出 schema.sql 中扫描到的表名> | 复用 / 新增 / 扩展字段 | 逐表说明 |
| 接口 | <逐一列出现有 Controller 的 @RequestMapping 前缀> | 复用 Controller / 扩展方法 / 全新接口 | 逐接口说明 |
| 错误码 | <逐一列出 ErrorCode.java 中已有枚举常量> | 直接复用 / 新增 | 逐场景说明 |

> 标记为「直接复用」的表和接口，在后续文档中**不得重复定义**。

## 5. 验收要点
3-6 条可执行的验收点，涵盖：功能路径、数据一致性、性能/容量、权限验证、Tushare 集成验证。
格式：`- [ ] P0 功能：通过 POST /api/xxx/create 可成功创建记录（对应 prototype/xxx.html 的「新建」按钮）`

## 6. 风险与 TODO
待确认事项（PRD 未明确）；原型图缺失或不完整的项；Tushare 接口依赖或限流问题；数据迁移/兼容策略。

## 7. 自检记录
本节由 AI 填写，记录本次设计过程中执行了哪些一致性检查。通过项从下方「自检清单（第 5 节）」中勾选。
```

---

### 3.2 `02-db-design.md` DB 设计文档

**核心改进**：在字段设计时引用原型图中的表单字段，确保 DB 设计与前端页面一致。

```markdown
# <需求名称> — 数据库设计方案

> 设计依据：
> - PRD：`sdlc/prd/<需求目录>/<PRD文件名>.md`
> - 原型图：`sdlc/prd/<需求目录>/prototype/` 下的 HTML 页面（表单字段 → 表字段映射）

---

## 0. 现有表复用检查（基于实时扫描结果）
| 现有表（来自实时扫描） | PRD 是否需要 | 复用决策 | 说明 |
|---------------------|-------------|---------|------|
| <表名 1> | 是 / 否 | 直接复用 / 扩展字段 / 不使用 | 如扩展，写出具体 ALTER TABLE 语句 |

> 「直接复用」的表不在 03-schema.sql 中重复定义；「扩展字段」的表在 03-schema.sql 中以 ALTER TABLE 增量方式给出；只有「不使用 + PRD 新能力」的表以 CREATE TABLE 新增。

## 1. 设计原则
- SQLite 单文件 + WAL 并发（与现有库一致）
- 表名/字段名：全小写 + 下划线分词（snake_case）
- 与现有 map-underscore-to-camel-case=true 保持一致，Java 端自动做小驼峰映射
- 主键策略：自增主键（INTEGER PRIMARY KEY AUTOINCREMENT）OR 自然主键（如 PRIMARY KEY(ts_code, trade_date)）
- 所有 ts_code 字段统一使用 6 位代码 + 市场后缀格式（如 000001.SZ）
- 日期字段：业务日期用 trade_date（YYYYMMDD 字符串），记录时间戳用 created_at / updated_at（ISO 格式）

## 2. 表清单
| 表名 | 用途 | 主键 | 预计量级 | 是否需要索引 | 对应原型图页面 |
|------|------|------|---------|------------|---------------|

## 3. 逐表字段设计

### 3.1 <表名 1>
> 字段来源：`sdlc/prd/.../prototype/xxx.html` 的「某某区块」表单字段。
> 字段映射原则：原型图 HTML 表单 name/id → DB 表 snake_case 字段。

| 字段名 | 类型 | 约束 | 说明 | 原型图中的来源 |
|--------|------|------|------|---------------|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT | PK | 主键 | — |
| `strategy_name` | TEXT | NOT NULL | 策略名称 | `prototype/xxx.html` 中 `<input id="strategyName">` |

> 关键约束：如有 UNIQUE(col1, col2) / NOT NULL / DEFAULT 等，必须在「约束」列明确标注。

### 3.2 <表名 2>
...（同上格式，逐表设计）...

## 4. DO 类映射（新增表对应的 Java DO 类设计）

### 4.1 <表名 1> → <类名>DO
```java
@Data
@TableName("<表名>")
public class <类名>DO {
    @TableId(type = IdType.AUTO) // 或 IdType.INPUT / IdType.NONE
    private Long id;
    private String strategyName; // 对应表字段 strategy_name；对应原型图 <input id="strategyName">
    private String tsCode;
    private String configJson;
    private String createdAt;
    private String updatedAt;
}
```

## 5. ER 关系
用文字描述表之间的 1:1 / 1:N / N:M 关系，指出关联外键字段。特别说明与现有表（stock_basic.ts_code / sys_user.id / trade_cal.cal_date）的关联。

## 6. 索引建议
列出所有需要的索引（`CREATE INDEX IF NOT EXISTS idx_xxx ON table(col);`），说明使用场景。

## 7. 数据迁移策略
- 新增表：只需追加 DDL 到 schema.sql（SQLite CREATE TABLE IF NOT EXISTS 幂等）
- 现有表结构变更：写出具体 ALTER 语句，说明是否需要数据回填、执行方式、失败回滚策略
- 重建表（SQLite 不支持 DROP/RENAME COLUMN）：写出完整的 CREATE-INSERT-DROP-RENAME 流程

## 8. 与现有表的兼容性
说明与 daily_quote / stock_basic / trade_cal / adj_factor / dividend / sys_user / sys_watchlist 等的关系。
关注：是否与现有表主键/UNIQUE 冲突、是否影响查询性能、是否与现有定时任务写入有竞争。

## 9. 验收相关
- [ ] 建表 SQL 执行无报错（SQLite 语法正确）
- [ ] 新增表与现有表通过 ts_code / trade_date 可正确 JOIN
- [ ] 主键/UNIQUE 约束生效
- [ ] 表字段与原型图中的表单字段一一对应
- [ ] 索引生效：EXPLAIN QUERY PLAN 确认关键查询走索引
```

---

### 3.3 `03-schema.sql` 建表 SQL

> 只输出本次新增/变更的 SQL，不要复制已有 schema.sql 的内容。
> 每条语句以 `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` / `ALTER TABLE` 开头。
> SQLite 数据类型：仅 INTEGER / TEXT / REAL。

```sql
-- ============================================================
-- <需求名称> 新增表与索引
-- 生成时间：YYYY-MM-DD
-- 设计依据：PRD sdlc/prd/<需求目录>/<PRD文件名>.md
--         + 原型图 sdlc/prd/<需求目录>/prototype/*.html
-- 说明：以下 DDL 请追加到 src/main/resources/schema.sql 的末尾
-- ============================================================

CREATE TABLE IF NOT EXISTS <表名 1> (
    <字段 1>    <类型>    <约束>,
    <字段 2>    <类型>    <约束>,
    PRIMARY KEY (<主键字段>)
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_<表名 1>_<字段名> ON <表名 1>(<字段名>);
```

---

### 3.4 `04-http-api.md` HTTP API 接口设计

**核心改进**：每个接口设计中引用原型图中对应的页面元素（按钮、表单、展示区域）。

> 设计前必须：先扫描 controller 目录（排除 PageController），形成「现有接口清单」。所有新增接口与清单做去重比对。

```markdown
# <需求名称> — HTTP API 接口设计

> 面向前端 / Thymeleaf JS 侧使用的接口规范。
> 请前端同学直接按本文档开发，字段含义、必填/可选、JSON 结构、分页方式必须写清楚。
>
> 设计依据：
> - PRD：`sdlc/prd/<需求目录>/<PRD文件名>.md`
> - 原型图：`sdlc/prd/<需求目录>/prototype/*.html`（每个接口标注对应页面的操作）

---

## 0. 现有接口复用检查（基于实时扫描结果）
| 现有 Controller（来自实时扫描） | 方法 | 路径 | 入参 | 复用决策 | 说明 |
|-------------------------------|------|------|------|---------|------|
| <Controller 名 1> | GET/POST | `/api/xxx/...` | ... | 直接复用 / 扩展方法 / 不使用 | ... |

> 「直接复用」的接口不在本设计文档中重复出现；「扩展方法」表示在现有 Controller 中新增方法，URL 前缀沿用已有 @RequestMapping。

## 1. 通用约定
- 根路径：`/api/<module>/...`，模块名统一小写，使用下划线分词
- 统一返回结构：`{ "code": 200, "message": "ok", "data": <...> }`
  - code=200 表示成功；错误码与 ErrorCode 定义一致
  - data 为业务数据（对象 / 数组 / 分页对象）
- Content-Type：`application/json`
- 鉴权：读接口默认需要登录；写接口/管理接口需要 @RequireAdmin + TOTP
- 分页：查询参数 page（int，默认 1，≥1）+ size（int，默认 20，1~100），响应 `{ "total": <int>, "records": [...] }`
- **JSON 字段强制小驼峰**：tsCode / tradeDate / createdAt（匹配 Spring Boot Jackson 默认行为），禁止下划线

## 2. 接口总览
| 方法 | URL | 描述 | 权限 | 对应原型图页面的操作 |
|------|-----|------|------|---------------------|

## 3. 逐接口详细设计

### 3.1 GET /api/xxx/list
> 对应原型图：`sdlc/prd/.../prototype/xxx.html` 的列表区域
> 前端触发：页面加载时自动调用，或筛选条件变更后调用

**请求参数（Query）**：
| 参数 | 类型 | 必填 | 默认值 | 范围/约束 | 说明 | 原型图来源 |
|------|------|------|--------|----------|------|-----------|
| `page` | int | 否 | 1 | ≥ 1 | 页码 | 页面底部分页器 |
| `size` | int | 否 | 20 | 1~100 | 每页条数 | 页面底部分页器 |
| `tsCode` | string | 否 | — | 格式如 000001.SZ | 股票代码过滤 | 顶部筛选条件输入框 |
| `keyword` | string | 否 | — | — | 名称/描述关键词搜索 | 顶部搜索框 |
| `status` | string | 否 | — | enum 值 | 状态筛选 | 顶部状态 Tab |

**响应 data**：
```json
{
  "total": 100,
  "records": [
    {
      "id": 1,
      "name": "示例策略",
      "status": "active",
      "tsCode": "000001.SZ",
      "description": "策略描述文本",
      "createdAt": "2026-06-19 15:00:00",
      "updatedAt": "2026-06-19 15:30:00"
    }
  ]
}
```

**错误码**：
- 401 UNAUTHORIZED：未登录
- 400 BAD_REQUEST：page/size 超出范围

### 3.2 POST /api/xxx/create
> 对应原型图：`sdlc/prd/.../prototype/xxx.html` 的「保存」按钮
> 前端触发：用户填写完表单后点击底部「保存」按钮

**请求参数（JSON Body）**：
```json
{
  "name": "我的策略",
  "description": "策略描述",
  "tsCode": "000001.SZ",
  "configJson": "{...}"
}
```

**请求字段说明**（与原型图表单一一对应）：
| 字段 | 类型 | 必填 | 约束 | 说明 | 原型图中的表单元素 |
|------|------|------|------|------|-------------------|
| `name` | string | 是 | 1~100 字符 | 策略名称 | `prototype/.../xxx.html` 中 `<input id="name" name="name">` |
| `description` | string | 否 | 最大 500 字符 | 描述 | `prototype/.../xxx.html` 中 `<textarea id="description">` |
| `tsCode` | string | 是 | 格式 000001.SZ | 股票代码 | 股票选择器 |
| `configJson` | string | 是 | 合法 JSON | 配置内容 | 配置器输出 |

**响应 data**：
```json
{
  "id": 1,
  "name": "我的策略",
  "status": "draft",
  "tsCode": "000001.SZ",
  "createdAt": "2026-06-20 10:00:00"
}
```

**前端后续操作**：保存成功后跳转到列表页。

**错误码**：
- 401 UNAUTHORIZED：未登录
- 403 FORBIDDEN：无权限（非管理员）
- 400 BAD_REQUEST：必填字段缺失 / tsCode 格式不正确 / configJson 解析失败
- 409 CONFLICT：记录已存在（UNIQUE 约束冲突）

### 3.3 <其他接口...>
...（同上模板，逐接口设计）...

## 4. 错误码汇总
| HTTP 状态码 | code | message | 触发场景 | 复用/新增 |
|------------|------|---------|----------|----------|

## 5. 验收相关
- [ ] 每个接口可通过 curl 成功调用，返回 JSON 结构符合设计
- [ ] 写接口未登录时返回 401，非管理员访问时返回 403
- [ ] 列表接口分页参数边界值测试通过
- [ ] 所有 JSON 字段均为小驼峰命名（无下划线）
- [ ] 响应中 code=200 表示成功，非 200 时前端可根据 message 展示错误
- [ ] 接口请求/响应字段与原型图中的表单字段已对齐检查

---

## 6. 执行步骤（严格按此顺序操作）

> 本节只定义**执行顺序**，不展开具体操作说明。各步骤的详细要求见「第 1 节 核心原则」和「第 3 节 文档模板」。

Step 1. 扫描 `sdlc/prd/` 目录 → 空目录则结束任务
Step 2. 选择目标目录 → 不携带参数时单目录直接选中、多目录让用户选择；携带参数时模糊匹配目录名
Step 3. 读取 PRD 文档 → 主 PRD（优先文件名含 "PRD"）+ 验收文档 + 补充文档
Step 4. 读取 `prototype/` 原型图 → 建立「原型图索引表（文件名 → 页面标题 → 核心功能）；不存在则标注
Step 5. 加载知识库 → `CLAUDE.md` + `.trae/rules/**/*.md`（按需读取匹配的规则文件）
Step 6. 扫描现有代码基底 → `schema.sql` / `*Controller.java` / `application.yml` / `ErrorCode.java` / `*Mapper.java` / `*ServiceImpl.java`
Step 7. 做复用决策 → 基于 Step 6 的扫描结果，对表/接口/错误码逐一判断：复用 / 扩展 / 新增
Step 8. 在 `sdlc/prd/<需求目录>/` 下新建设计文档 → 已有文件加日期后缀，不覆盖
Step 9. 产出 4 份文档 → 按第 3 节模板撰写，引用 PRD 和原型图路径
Step 10. 自检与修复 → 按第 5 节「自检清单」逐条检查并修复
Step 11. 输出总结 → 列出文件路径 + 记录自检通过项 + 标注 PRD/原型图信息不足项

---

## 7. 自检清单

> 本节是 Step 10 的检查依据。AI 在产出 4 份文档后，按本节逐条自检并修复。
> 通过标准：所有与本次 PRD 相关的检查项全部通过。与本次 PRD 无关的检查项标注「N/A」。

### 7.1 全局一致性检查

- [ ] 所有表名 / 字段名为 `snake_case`（全小写 + 下划线分词）
- [ ] 所有 JSON 字段（请求/响应）为 `camelCase`（小驼峰），**绝对没有下划线命名出现在 JSON 中**
- [ ] SQLite 数据类型正确（仅 INTEGER / TEXT / REAL），没有使用 MySQL 专有类型
- [ ] 没有设计与现有 Controller 路径语义重复的新接口
- [ ] 新增错误码（如有）没有与现有 `ErrorCode.java` 中含义重复
- [ ] `PageController` 未被当作 REST API 参与复用判断
- [ ] **01-main-design.md 头部包含「参考资料索引」，引用了 PRD 和原型图文件路径**
- [ ] **DTO / 表字段与原型图中的表单字段做了对齐检查（如原型图存在）**

### 7.2 `01-main-design.md` 检查

- [ ] 「4.7 复用清单」基于 Step 6 实时扫描结果填写，而非固定清单
- [ ] 「2. 原型图分析」章节存在，且列出了每个原型页面的核心功能、表单字段和操作
- [ ] 模块划分的 Java 包路径与现有项目一致（`com.arthur.stock.*`）
- [ ] Service 层遵循 `@Service @RequiredArgsConstructor @Slf4j` 风格
- [ ] 如涉及定时任务，已写明 cron 表达式与时间窗
- [ ] 如涉及缓存，已写明 key 命名 + 过期策略 + 失效条件
- [ ] 如涉及 Tushare 新接口，已写明预估接口名 + 限流策略
- [ ] 如涉及权限，已区分哪些接口需要 `@RequireAdmin`、哪些登录即可

### 7.3 `02-db-design.md` 检查

- [ ] 「0. 现有表复用检查」基于 Step 6 实时扫描结果填写，而非固定清单
- [ ] 主键策略合理（自增 vs 自然主键，按表业务特征选择）
- [ ] 股票代码字段是否命名为 `ts_code`（如有 symbol 字段，说明两者映射关系）
- [ ] 日期字段遵循 `trade_date`（YYYYMMDD）/ `created_at` / `updated_at`（ISO 格式）规范
- [ ] 「4. DO 类映射」小节已填写，DO 类命名遵循 `*DO` 后缀
- [ ] 如有 UNIQUE 约束，已在逐表设计中明确标注
- [ ] **字段设计中标注了原型图来源（如原型图存在）**
- [ ] 索引建议与预期查询场景匹配（查询条件/排序字段已建索引）

### 7.4 `03-schema.sql` 检查

- [ ] 每条语句以 `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` / `ALTER TABLE` 开头
- [ ] 字段顺序与 `02-db-design.md` 中表格顺序完全一致
- [ ] 没有重复定义 schema.sql 中已有的表
- [ ] 逗号位置与现有 schema.sql 风格一致（字段名左对齐，类型与约束右对齐）
- [ ] **文件头部包含「设计依据」注释，引用了 PRD 和原型图路径**

### 7.5 `04-http-api.md` 检查

- [ ] 「0. 现有接口复用检查」基于 Step 6 实时扫描结果填写，而非固定清单
- [ ] 每个接口的 URL 路径与现有 Controller 的 `@RequestMapping` 前缀无冲突
- [ ] 每个接口的请求参数都明确标注：类型、是否必填、默认值、范围/约束
- [ ] 每个接口的响应 data 结构给出**完整的 JSON 示例**，包含所有字段
- [ ] JSON 字段命名为小驼峰（`tsCode` / `tradeDate` / `createdAt`），没有下划线
- [ ] 分页接口的响应统一为 `{ "total": <int>, "records": [...] }`
- [ ] 读接口声明需要登录；写接口/管理接口声明需要 `@RequireAdmin`
- [ ] 错误码与 `ErrorCode.java` 中的定义一致，没有新增重复含义的 code
- [ ] **每个接口都标注了「对应原型图页面的操作」（如原型图存在）**
- [ ] **关键验证**：假设自己是前端开发者，仅阅读本接口设计 + 原型图，能否直接写出正确的 fetch 调用？如果有任何一个字段的含义/是否必填/数据类型/默认值不明确 → 不合格，必须补充后通过。

---

## 8. 风格约束

1. **文档语言**：**中文**（与 PRD 及现有 design 文档风格一致）
2. **代码块语言标注**：SQL 用 ` ```sql `，JSON 用 ` ```json `，Java 用 ` ```java `
3. **保持与现有架构一致**：任何与 `CLAUDE.md` / `.trae/rules/` / `schema.sql` 冲突的设计，需要在主设计文档的「6. 风险与 TODO」中显式列出并说明替代方案
4. **不要编造具体 Tushare 接口名**：若 PRD 涉及新的外部数据源但接口未确定，写成 `TushareClient.<待定方法>` 并标注 TODO
5. **字段命名遵循现有习惯**：股票代码一律使用 `ts_code`（如 `000001.SZ`）与 `symbol`（如 `000001`）双字段，日期统一使用 `trade_date`（YYYYMMDD 字符串）或 `created_at` / `updated_at`（ISO 字符串）
6. **JSON 响应字段强制小驼峰**：面向前端的 HTTP API 文档中，所有 JSON 字段名必须为小驼峰（`tsCode`、`tradeDate`、`createdAt`），以匹配 Spring Boot Jackson 默认行为
7. **原型图引用的一致性**：引用原型图时使用相对项目根目录的完整路径（`sdlc/prd/<目录>/prototype/<文件>.html`）
