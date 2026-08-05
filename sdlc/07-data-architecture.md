# 数据架构与数据库设计

> **覆盖范围**：数据分层模型、C 端用户业务表设计、索引策略、Tushare 业务表概述、Redis 数据规划、数据生命周期管理、数据安全与权限
> **来源**：由 `ai-stock-analysis-platform-plan.md` 第四章数据架构与第十章数据库设计扩展优化而成
> **关联文档**：02 系统架构设计、04 用户个性化功能设计、05 报告中心设计、06 AI 对话中枢设计、08 技术选型

---

## 一、数据架构总览

### 1.1 双存储模型

平台的数据存储由 **MySQL** 和 **Redis Stack** 两个组件构成。MySQL 承载所有结构化数据，按写入权限分为 Tushare 业务表（25+ 张，ERP 独占写入、C 端只读）和 C 端用户业务表（10 张，C 端独占读写）。Redis Stack 承担会话缓存、向量索引、实时行情缓存和任务队列四类职责，是 AI 对话和个性化推送的性能底座。

```
┌──────────────────────────────────────────────┐
│                MySQL 数据库                   │
│                                              │
│  ┌──────────────────┐  ┌──────────────────┐  │
│  │ Tushare 业务表    │  │ C 端用户业务表     │  │
│  │ (30+张，ERP 写入  │  │ (10张，C端独占     │  │
│  │  C 端只读)       │  │  读写)            │  │
│  │                  │  │                  │  │
│  │ stock_basic      │  │ chat_conversation│  │
│  │ daily_quote      │  │ chat_message     │  │
│  │ daily_basic      │  │ user_watchlist   │  │
│  │ income           │  │ user_portfolio   │  │
│  │ stock_moneyflow  │  │ monitor_rule     │  │
│  │ top_list         │  │ user_task        │  │
│  │ hk_hold          │  │ user_task_log    │  │
│  │ ...              │  │ analysis_report  │  │
│  │                  │  │ push_notification│  │
│  │                  │  │ ai_analysis_audit│  │
│  └──────────────────┘  └──────────────────┘  │
└──────────────────────────────────────────────┘
            │                      │
            ▼                      ▼
┌──────────────────────────────────────────────┐
│           Redis Stack（缓存 + 向量）          │
│  会话缓存 │ 向量索引(RediSearch)              │
│  实时行情缓存 │ 任务队列(Stream)               │
└──────────────────────────────────────────────┘
```

读写权限隔离是双存储模型的核心约束。ERP 系统独占 Tushare 业务表的写入权限（数据采集与更新），C 端应用对这些表只能只读查询，避免两个进程同时写入造成锁竞争。C 端用户业务表由 C 端应用独占读写，ERP 完全不触及。这种隔离让数据写入入口收敛到单一进程，排查数据问题时能快速定位责任方。

### 1.2 数据分层模型

数据从 Tushare API 流入到最终服务用户，经过四层加工，每层职责清晰、边界明确。

| 数据层 | 位置 | 内容 | 写入方 | 生命周期 |
|--------|------|------|--------|----------|
| **原始数据层** | Tushare API | 接口返回的原始 JSON（fields + items） | Tushare | 瞬态，采集后即解析 |
| **清洗数据层** | ERP 进程内存 | 前复权计算、剔除停牌/涨跌停/ST、字段类型转换 | ERP Service | 瞬态，清洗后即落库 |
| **业务数据层** | MySQL Tushare 表 | 清洗后的结构化数据（日线行情、财务三表、资金流向等） | ERP | 持久化，按年分区 |
| **用户数据层** | MySQL C 端表 + Redis | 对话记录、自选股、报告、会话缓存、行情缓存 | C 端应用 | 持久化 + 缓存 TTL |

**原始数据层**是 Tushare API 返回的原始响应，ERP 的 TushareClient 解析 fields[]+items[][] 后映射为 DTO，这一层不做任何业务加工，只做协议解析。**清洗数据层**在 ERP Service 的内存中完成，核心清洗动作包括前复权计算（price × adj_factor）、剔除当日停牌与涨跌停标的、过滤 ST 股票，清洗结果直接写入 MySQL。清洗逻辑不在数据库层做，是为了避免数据库承担计算压力，保持入库操作的纯粹性。

**业务数据层**是清洗后的持久化数据，存放在 MySQL 的 Tushare 业务表中，是 C 端所有数据查询的唯一数据源。**用户数据层**包含 C 端用户业务表（MySQL）和 Redis 缓存，前者持久化用户的对话、自选股、报告等资产，后者缓存热点行情和会话上下文以降低数据库查询压力。

### 1.3 数据流向

数据在平台中的流动是单向的，从外部数据源最终流向终端用户，中间经过采集、清洗、存储、查询、缓存五个环节。

```
Tushare API  ──采集──>  ERP 进程内存  ──清洗──>  MySQL Tushare 表
(原始数据)              (前复权/剔除)             (业务数据层)
                                                       │
                                                       │ C 端只读查询
                                                       ▼
                                               C 端应用后端
                                                       │
                                          ┌────────────┴───────────┐
                                          ▼                        ▼
                                   Redis 缓存               AI 对话中枢
                                   (命中则直接返回)         (Function Calling 取数)
                                          │                        │
                                          └────────────┬───────────┘
                                                       ▼
                                                  终端用户
                                            (股票卡片/图表/报告)
```

ERP 采集任务由 Quartz 调度，行情数据每日 16:00 盘后采集，财务数据每周日 17:00 更新，事件数据（龙虎榜、融资融券、北向资金）每日 16:00 采集。采集遵循五条铁律：单次返回不超过 5000 且必须 offset+limit 循环分页、offset 超过 10 万时按时间或标的降维拆分、不绕过 RateLimiter、事务粒度最小化禁止跨 API 大事务、批量写入每批 500 行禁止循环逐条插入。这些铁律保证采集过程不会长时间锁表，从而不阻塞 C 端的只读查询。

C 端查询走"先缓存后回源"的路径。热点行情数据先查 Caffeine 本地缓存，未命中再查 Redis，最后回源 MySQL。查询结果按 ts_code + trade_date 做复合 key 缓存回 Redis，行情数据 TTL 设为当日有效，估值数据 TTL 设为 1 小时。这种多级缓存让大量重复查询（如多用户同时查茅台行情）只命中一次数据库。

### 1.4 数据治理原则

平台的数据治理遵循三条原则，确保数据从采集到呈现的全链路可信。

**数据可溯源**要求每个数据点都能追溯到来源。Tushare 业务表中的每条记录都带有 ts_code 和 trade_date（或 end_date）作为业务主键，分析报告中的 data_snapshot 字段记录生成时的数据快照，AI 审计日志记录每次模型调用的工具调用链和返回结果。当用户对某个数据点存疑时，可以从报告快照追溯到具体的表、日期和接口。

**口径一致**要求同一指标在不同场景下计算方式统一。技术指标计算（MA/MACD/KDJ 等）统一由 Python 计算服务承担，估值分位计算统一由研究方法引擎的确定性代码完成，AI 只做文字解读不做数值计算。这避免了"AI 算一遍、代码算一遍、结果不一样"的信任危机。

**质量可控**通过 ERP 的 DataCheckable 接口实现。每张 Tushare 业务表的 Service 实现 3-5 个核心检测指标，覆盖数据量级、最新数据日期、缺失交易日、重复数据、字段异常值等。检测结果写入 data_governance_metric 表，在管理页面以状态灯（绿色正常/黄色延迟/红色异常）展示，运维人员能第一时间发现数据断更。

---

## 二、C 端用户业务表设计

### 2.1 设计规范

C 端用户业务表是全新设计的表结构，不沿用 stock-pulse 的字段类型惯例。stock-pulse 作为本地部署系统，使用 VARCHAR 存储日期时间（如 `VARCHAR(8)` 存 yyyyMMdd、`VARCHAR(32)` 存时间戳），这是当时为兼容多数据源的选择。本平台作为 C 端 SaaS 互联网产品，时间字段统一采用 MySQL 原生类型，原因有三：DATETIME 占用 5 字节而 VARCHAR(19) 占用 19 字节，存储更紧凑；时间范围查询（BETWEEN、大于小于）在 DATETIME 上的索引效率更高；数据生命周期管理（按日期归档、清理）依赖原生时间类型才能高效执行。

每张表统一包含以下审计与治理字段：`id`（BIGINT 自增主键）、`created_at`（DATETIME 创建时间）、`updated_at`（DATETIME 更新时间）、`is_deleted`（TINYINT 软删除标记，0=正常 1=已删除）、`version`（INT 乐观锁版本号，默认 1）。软删除用于用户删除对话、报告等场景，数据不物理删除而是标记 is_deleted=1，便于误操作恢复和审计追溯。乐观锁版本号用于并发更新场景（如多个请求同时修改同一监控规则），通过 WHERE version = ? 条件避免覆盖更新。

字段类型遵循以下规则：金额与价格字段使用 DECIMAL(20,4) 与 Tushare 表保持精度一致；布尔字段使用 TINYINT 而非 VARCHAR；枚举类字段（如 rule_type、report_type）使用 VARCHAR 存储枚举字符串值，配合 Java 侧 DisplayableEnum 枚举类；JSON 结构数据使用 MySQL JSON 类型而非 TEXT，便于字段级查询和校验。

### 2.2 表清单与 ER 关系

C 端用户业务表共 10 张，覆盖对话、个性化、任务、报告、推送、审计六个业务域。

| 业务域 | 表名 | 用途 |
|--------|------|------|
| 对话 | chat_conversation | 对话会话 |
| 对话 | chat_message | 对话消息（ChatMemory 持久化） |
| 个性化 | user_watchlist | 自选股 |
| 个性化 | user_portfolio | 模拟持仓 |
| 个性化 | monitor_rule | 盯盘监控规则 |
| 任务 | user_task | 用户定时任务 |
| 任务 | user_task_log | 任务执行历史 |
| 报告 | analysis_report | 分析报告 |
| 推送 | push_notification | 推送通知 |
| 审计 | ai_analysis_audit | AI 分析审计日志 |

各表之间通过**逻辑外键**关联，不使用物理外键约束。逻辑外键是指表中存储关联 ID 但不建 FOREIGN KEY 约束，关联关系由应用层保证。这种设计避免了物理外键带来的写入性能损耗和级联锁问题，也符合微服务化拆分后的演进方向。以下是各表的关联关系：

```
chat_conversation (1) ──── (N) chat_message
        │                        [chat_message.conversation_id -> chat_conversation.id]
        │
        ├──── (N) analysis_report
        │             [analysis_report.conversation_id -> chat_conversation.id]
        │
        └──── (N) ai_analysis_audit
                      [ai_analysis_audit.conversation_id -> chat_conversation.id]

user_task (1) ──── (N) user_task_log
        │                    [user_task_log.task_id -> user_task.id]
        │
        └──── (触发) analysis_report + push_notification
                  [任务执行后生成报告，报告 ID 回填到 user_task_log.report_id]

analysis_report (1) ──── (N) push_notification
                          [push_notification.report_id -> analysis_report.id]

monitor_rule ──── (触发) push_notification
                   [规则触发后生成通知，通知内容引用规则与股票]

user_watchlist / user_portfolio ──── (引用) Tushare stock_basic
                                    [通过 ts_code 关联，跨表逻辑引用]
```

对话会话是核心枢纽表，向下关联消息、报告和审计日志。定时任务通过执行日志间接关联分析报告（执行后生成报告，report_id 回填到日志）。监控规则和定时任务触发后都会产生推送通知，通知中通过 report_id 引用关联的分析报告。自选股和持仓通过 ts_code 字段与 Tushare 的 stock_basic 表形成跨域逻辑引用，但不建立物理外键。

### 2.3 对话会话表 chat_conversation

对话会话表记录用户每次对话的元信息，是 ChatMemory 的持久化入口。每条会话记录所属用户、标题和最后活跃时间，左侧边栏的会话列表即从此表读取。

```sql
CREATE TABLE chat_conversation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id         BIGINT NOT NULL COMMENT '所属用户ID',
    title           VARCHAR(128) COMMENT '会话标题（默认取首条消息摘要，用户可重命名）',
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态：ACTIVE=活跃/ARCHIVED=归档',
    message_count   INT NOT NULL DEFAULT 0 COMMENT '消息总数（冗余计数，避免 COUNT 查询）',
    last_message_at DATETIME COMMENT '最后一条消息时间（用于会话列表按活跃度排序）',
    is_deleted      TINYINT NOT NULL DEFAULT 0 COMMENT '软删除：0=正常，1=已删除',
    version         INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_conv_user_deleted_updated (user_id, is_deleted, updated_at),
    INDEX idx_conv_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话会话表';
```

相比原报告 DDL，本表补充了 status（会话归档状态）、message_count（冗余消息计数）、last_message_at（最后消息时间）三个业务字段。message_count 避免了会话列表展示时对 chat_message 表做 COUNT 聚合查询，last_message_at 让会话列表能按最近活跃时间排序而无需 JOIN 消息表。原报告用 VARCHAR(32) 存储时间戳，本设计改为 DATETIME，并补充了 is_deleted 和 version 治理字段。

### 2.4 对话消息表 chat_message

对话消息表存储每条对话消息的完整内容，包括用户消息和 AI 回复。ChatMemory 的窗口消息持久化在此表，超过窗口的旧消息由 AI 摘要后存入长期记忆。这是数据量增长最快的表，与用户活跃度线性相关。

```sql
CREATE TABLE chat_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    conversation_id BIGINT NOT NULL COMMENT '所属会话ID（逻辑外键 -> chat_conversation.id）',
    role            VARCHAR(16) NOT NULL COMMENT '消息角色：user=用户消息/assistant=AI回复/tool=工具调用结果',
    content         MEDIUMTEXT NOT NULL COMMENT '消息内容（用户提问文本或AI回复的Markdown/富媒体JSON）',
    tool_calls_json TEXT COMMENT 'AI发起的工具调用（JSON数组，含工具名与参数）',
    tool_result_json TEXT COMMENT '工具调用的返回结果（JSON）',
    token_count     INT DEFAULT 0 COMMENT '本条消息的token数（用于成本统计）',
    is_deleted      TINYINT NOT NULL DEFAULT 0 COMMENT '软删除：0=正常，1=已删除',
    version         INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_msg_conv_created (conversation_id, created_at),
    INDEX idx_msg_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息表（ChatMemory持久化）';
```

content 字段从 TEXT 升级为 MEDIUMTEXT，因为 AI 回复可能包含完整的 HTML 报告内容，TEXT 的 64KB 限制可能不够。补充的 token_count 字段记录每条消息的 token 消耗，配合 ai_analysis_audit 表实现精细化的成本追踪。索引 (conversation_id, created_at) 是核心查询路径：加载会话历史时按会话 ID 过滤并按时间正序排列，联合索引同时覆盖过滤和排序，避免 filesort。

### 2.5 自选股表 user_watchlist

自选股表记录用户关注的股票，支持分组管理和目标价提醒。用户可通过对话（"加个自选茅台"）或页面操作添加，左侧边栏的自选股快捷面板从此表读取。

```sql
CREATE TABLE user_watchlist (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id       BIGINT NOT NULL COMMENT '所属用户ID',
    ts_code       VARCHAR(16) NOT NULL COMMENT '股票代码（如 600519.SH，逻辑引用 Tushare stock_basic）',
    group_name    VARCHAR(64) NOT NULL DEFAULT '默认' COMMENT '分组名称（如 白酒/新能源/长期关注）',
    target_price  DECIMAL(20,4) COMMENT '目标价提醒（达到此价时触发通知）',
    note          VARCHAR(255) COMMENT '用户备注',
    sort_order    INT NOT NULL DEFAULT 0 COMMENT '组内排序序号（越小越靠前）',
    is_deleted    TINYINT NOT NULL DEFAULT 0 COMMENT '软删除：0=正常，1=已删除',
    version       INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_watchlist_user_code_group (user_id, ts_code, group_name),
    INDEX idx_watchlist_user_group_sort (user_id, group_name, sort_order),
    INDEX idx_watchlist_code (ts_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自选股表';
```

target_price 从 DECIMAL(12,2) 调整为 DECIMAL(20,4)，与 Tushare 行情表的价格精度保持一致，避免精度截断。补充了唯一约束 (user_id, ts_code, group_name) 防止同一用户在同一分组下重复添加同一股票。group_name 当前以字符串存储分组名，后续若分组管理复杂化，可拆出独立的 user_watchlist_group 表并通过 group_id 关联，当前阶段保持简单设计。联合索引 (user_id, group_name, sort_order) 覆盖了"展示某用户某分组的自选股并按排序展示"这一最高频查询场景。

### 2.6 模拟持仓表 user_portfolio

模拟持仓表记录用户手动录入的持仓信息（非实盘），系统据此自动计算盈亏、收益率和持仓占比。持仓变动时可触发分析推送（如浮亏超过 10% 预警）。

```sql
CREATE TABLE user_portfolio (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id       BIGINT NOT NULL COMMENT '所属用户ID',
    ts_code       VARCHAR(16) NOT NULL COMMENT '股票代码（逻辑引用 Tushare stock_basic）',
    buy_price     DECIMAL(20,4) NOT NULL COMMENT '买入价格（元）',
    quantity      INT NOT NULL COMMENT '持仓数量（股）',
    buy_date      DATE NOT NULL COMMENT '买入日期',
    sell_price    DECIMAL(20,4) COMMENT '卖出价格（元，已清仓时填写）',
    sell_date     DATE COMMENT '卖出日期（已清仓时填写）',
    status        VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '持仓状态：OPEN=持仓中/CLOSED=已清仓',
    note          VARCHAR(255) COMMENT '用户备注',
    is_deleted    TINYINT NOT NULL DEFAULT 0 COMMENT '软删除：0=正常，1=已删除',
    version       INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_portfolio_user_status (user_id, status),
    INDEX idx_portfolio_code (ts_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟持仓表';
```

原报告的 buy_date 用 VARCHAR(32) 存储，本设计改为 DATE 类型，买入日期只需日期精度无需时间。buy_price 从 DECIMAL(12,2) 调整为 DECIMAL(20,4)。补充了 sell_price、sell_date、status 三个字段支持持仓的全生命周期管理：用户清仓后记录卖出信息并将状态置为 CLOSED，系统据此计算已实现盈亏。索引 (user_id, status) 让"展示用户当前持仓"这一高频查询（WHERE user_id = ? AND status = 'OPEN'）直接命中。

### 2.7 盯盘监控规则表 monitor_rule

盯盘监控规则表存储用户通过对话创建的盯盘规则，由 Quartz 调度引擎按规则类型定期检查触发条件。这是个性化功能的核心数据载体。

```sql
CREATE TABLE monitor_rule (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id         BIGINT NOT NULL COMMENT '所属用户ID',
    ts_code         VARCHAR(16) NOT NULL COMMENT '监控股票代码（逻辑引用 Tushare stock_basic）',
    rule_type       VARCHAR(32) NOT NULL COMMENT '规则类型：PRICE=价格/CHANGE=涨跌幅/TECHNICAL=技术指标/MONEYFLOW=资金流向/FINANCIAL=财务事件/ANNOUNCEMENT=公告/TOP_LIST=龙虎榜/HK_HOLD=北向资金',
    rule_params     JSON NOT NULL COMMENT '规则参数（JSON，如 {"operator":"<","threshold":1700}）',
    push_channels   VARCHAR(128) NOT NULL DEFAULT 'IN_APP' COMMENT '推送渠道（逗号分隔：IN_APP/EMAIL/WECHAT）',
    enabled         TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1=启用，0=停用',
    trigger_count   INT NOT NULL DEFAULT 0 COMMENT '累计触发次数',
    last_triggered_at DATETIME COMMENT '最后触发时间',
    is_deleted      TINYINT NOT NULL DEFAULT 0 COMMENT '软删除：0=正常，1=已删除',
    version         INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_monitor_user_enabled (user_id, enabled, is_deleted),
    INDEX idx_monitor_scan (ts_code, enabled, rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盯盘监控规则表';
```

rule_params 从 TEXT 改为 JSON 类型，MySQL 5.7+ 原生支持 JSON 字段的存储和部分查询，能对规则参数做字段级提取。补充了 trigger_count（触发计数）和 last_triggered_at（最后触发时间），前者用于规则管理页面展示活跃度，后者用于防抖（同一规则短时间内不重复触发）。两个核心索引服务于不同场景：(user_id, enabled, is_deleted) 支撑用户规则列表查询，(ts_code, enabled, rule_type) 支撑监控引擎按股票扫描所有生效规则。

### 2.8 用户定时任务表 user_task

用户定时任务表存储用户通过对话创建的个性化定时任务，如晨报、收盘分析、板块周报等。Quartz 调度器根据 cron_expr 和 next_run_at 调度执行。

```sql
CREATE TABLE user_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id         BIGINT NOT NULL COMMENT '所属用户ID',
    task_name       VARCHAR(128) NOT NULL COMMENT '任务名称',
    task_type       VARCHAR(32) NOT NULL COMMENT '任务类型：MORNING_NOTE=晨报/CLOSE_ANALYSIS=收盘分析/SECTOR_WEEKLY=板块周报/PORTFOLIO_REVIEW=持仓复盘/EVENT_TRACK=事件跟踪',
    cron_expr       VARCHAR(64) NOT NULL COMMENT 'cron表达式（如 0 8 * * ? 每天早8点）',
    params_json     JSON NOT NULL COMMENT '任务参数（JSON，如 {"ts_code":"600519.SH","industry":"白酒"}）',
    push_channels   VARCHAR(128) NOT NULL DEFAULT 'IN_APP' COMMENT '推送渠道（逗号分隔）',
    enabled         TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1=启用，0=停用',
    run_count       INT NOT NULL DEFAULT 0 COMMENT '累计执行次数',
    last_run_status VARCHAR(16) COMMENT '上次执行状态：SUCCESS/FAILED',
    last_run_at     DATETIME COMMENT '上次执行时间',
    next_run_at     DATETIME COMMENT '下次执行时间（调度器据此扫描到期任务）',
    is_deleted      TINYINT NOT NULL DEFAULT 0 COMMENT '软删除：0=正常，1=已删除',
    version         INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_task_user_enabled (user_id, enabled, is_deleted),
    INDEX idx_task_schedule (next_run_at, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户定时任务表';
```

params_json 从 TEXT 改为 JSON 类型。补充了 run_count（执行计数）、last_run_status（上次执行状态）两个字段，前者展示任务活跃度，后者让用户在任务管理页面快速看到任务是否正常运行。索引 (next_run_at, enabled) 是调度器的核心查询路径：调度器每分钟扫描 WHERE next_run_at <= NOW() AND enabled = 1 的任务并触发执行，联合索引让扫描直接走索引而不回表。

### 2.9 任务执行历史表 user_task_log

任务执行历史表记录每次定时任务执行的完整轨迹，用于执行历史查看和问题排查。

```sql
CREATE TABLE user_task_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    task_id         BIGINT NOT NULL COMMENT '所属任务ID（逻辑外键 -> user_task.id）',
    run_at          DATETIME NOT NULL COMMENT '本次执行时间',
    status          VARCHAR(16) NOT NULL COMMENT '执行状态：SUCCESS/FAILED/TIMEOUT',
    report_id       BIGINT COMMENT '生成报告ID（逻辑外键 -> analysis_report.id，执行成功时填写）',
    error_summary   VARCHAR(1024) COMMENT '错误摘要（失败时记录，截断1024字符）',
    duration_ms     INT COMMENT '执行耗时（毫秒）',
    tokens_consumed INT DEFAULT 0 COMMENT '本次执行消耗的token总数',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_log_task_run (task_id, run_at),
    INDEX idx_log_run_at (run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务执行历史表';
```

run_at 从 VARCHAR(32) 改为 DATETIME。补充了 tokens_consumed 字段记录每次执行消耗的 token 数，与 ai_analysis_audit 表配合实现成本归因。本表不设软删除和版本号字段，因为执行日志是只追加的流水记录，不需要修改和删除（清理由生命周期管理任务按时间批量删除）。索引 (task_id, run_at) 支撑"查看某任务的历史执行记录按时间倒序"，(run_at) 支撑生命周期清理任务按时间范围扫描。

### 2.10 分析报告表 analysis_report

分析报告表存储 AI 生成的分析报告，是用户的核心资产。报告在对话中生成后独立存入此表，由报告中心统一管理，支持按类型、股票、行业筛选、搜索、收藏、对比。

```sql
CREATE TABLE analysis_report (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id         BIGINT NOT NULL COMMENT '所属用户ID',
    conversation_id BIGINT COMMENT '生成报告的会话ID（逻辑外键 -> chat_conversation.id）',
    report_type     VARCHAR(32) NOT NULL COMMENT '报告类型：STOCK_DEEP=个股深度/SECTOR=板块分析/SCREENING=选股筛选/PORTFOLIO=持仓诊断/MARKET=市场综述/SCHEDULED=定时任务报告',
    ts_code         VARCHAR(16) COMMENT '关联股票代码（个股报告适用，逻辑引用 stock_basic）',
    industry        VARCHAR(64) COMMENT '关联行业（板块报告适用，如 白酒）',
    title           VARCHAR(255) NOT NULL COMMENT '报告标题',
    summary         TEXT NOT NULL COMMENT '报告摘要（报告中心列表展示用）',
    content         LONGTEXT NOT NULL COMMENT '完整报告内容（HTML格式）',
    data_snapshot   JSON COMMENT '生成时的数据快照（JSON，记录关键指标与来源，用于数据可追溯）',
    status          VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT '报告状态：DRAFT=草稿/PUBLISHED=已发布',
    is_favorited    TINYINT NOT NULL DEFAULT 0 COMMENT '是否收藏：0=否，1=是',
    view_count      INT NOT NULL DEFAULT 0 COMMENT '查看次数（冗余计数）',
    is_deleted      TINYINT NOT NULL DEFAULT 0 COMMENT '软删除：0=正常，1=已删除',
    version         INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_report_user_type_fav (user_id, report_type, is_favorited, is_deleted),
    INDEX idx_report_user_created (user_id, is_deleted, created_at),
    INDEX idx_report_code_created (ts_code, created_at),
    INDEX idx_report_industry (industry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析报告表';
```

data_snapshot 从 TEXT 改为 JSON 类型，便于对快照数据做字段级查询。补充了 status（报告草稿/发布状态）和 view_count（查看计数）字段。索引设计覆盖报告中心的四个高频查询场景：(user_id, report_type, is_favorited, is_deleted) 支撑"我的收藏报告按类型筛选"，(user_id, is_deleted, created_at) 支撑"我的全部报告按时间倒序"，(ts_code, created_at) 支撑"查看某只股票的所有历史报告"，(industry) 支撑按行业筛选。原报告的 idx_report_fav(user_id, is_favorited) 被升级为四列联合索引，覆盖更多筛选维度。

### 2.11 推送通知表 push_notification

推送通知表统一管理所有推送给用户的消息，包括盯盘触发、任务结果和系统通知。通知中心铃铛的未读计数和通知列表均从此表读取。

```sql
CREATE TABLE push_notification (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id         BIGINT NOT NULL COMMENT '接收用户ID',
    type            VARCHAR(32) NOT NULL COMMENT '通知类型：MONITOR=盯盘触发/TASK=任务结果/SYSTEM=系统通知',
    title           VARCHAR(255) NOT NULL COMMENT '通知标题',
    summary         TEXT COMMENT '通知摘要（列表展示用）',
    content         JSON COMMENT '富媒体内容（JSON，含股票卡片/图表数据等）',
    report_id       BIGINT COMMENT '关联报告ID（逻辑外键 -> analysis_report.id，点击通知跳转报告详情）',
    is_read         TINYINT NOT NULL DEFAULT 0 COMMENT '是否已读：0=未读，1=已读',
    read_at         DATETIME COMMENT '阅读时间',
    is_deleted      TINYINT NOT NULL DEFAULT 0 COMMENT '软删除：0=正常，1=已删除',
    version         INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_notif_user_read_created (user_id, is_read, is_deleted, created_at),
    INDEX idx_notif_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推送通知表';
```

content 从 TEXT 改为 JSON 类型，因为推送内容是结构化的富媒体数据（股票卡片、图表配置等），JSON 类型便于前端按字段解析。补充了 read_at（阅读时间）字段用于阅读行为分析。索引 (user_id, is_read, is_deleted, created_at) 是通知中心的核心查询路径：未读通知列表 WHERE user_id = ? AND is_read = 0 AND is_deleted = 0 ORDER BY created_at DESC，四列联合索引让过滤和排序全部走索引。本表是数据生命周期管理的重点，通知超过 3 个月后由清理任务删除，idx_notif_created 索引支撑按时间范围的批量删除。

### 2.12 AI 分析审计日志表 ai_analysis_audit

AI 分析审计日志表记录每次 AI 对话的完整调用链，包括用户查询、意图判断、工具调用、模型响应和成本数据。这是数据可溯源原则的落地表。

```sql
CREATE TABLE ai_analysis_audit (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id         BIGINT NOT NULL COMMENT '发起用户ID',
    conversation_id BIGINT COMMENT '所属会话ID（逻辑外键 -> chat_conversation.id）',
    user_query      TEXT NOT NULL COMMENT '用户原始提问',
    intent          VARCHAR(32) COMMENT '识别意图：QUERY=轻量查询/ANALYSIS=单方法分析/RESEARCH=多步骤研究/ACTION=操作指令/SCREEN=选股筛选/CHAT=闲聊',
    tools_called    JSON COMMENT '工具调用链（JSON数组，记录调用的工具名、参数、耗时）',
    tool_results    JSON COMMENT '工具返回结果摘要（JSON）',
    ai_response     MEDIUMTEXT COMMENT 'AI回复内容',
    model_name      VARCHAR(64) COMMENT '调用的模型名称（如 qwen-turbo/qwen-plus）',
    tokens_input    INT DEFAULT 0 COMMENT '输入token数',
    tokens_output   INT DEFAULT 0 COMMENT '输出token数',
    tokens_total    INT GENERATED ALWAYS AS (tokens_input + tokens_output) STORED COMMENT '总token数（计算列）',
    cost_cents      INT DEFAULT 0 COMMENT '本次调用成本（分，用于成本统计）',
    status          VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '调用状态：SUCCESS/FAILED/TIMEOUT',
    latency_ms      INT COMMENT '总延迟（毫秒，从接收到返回）',
    is_deleted      TINYINT NOT NULL DEFAULT 0 COMMENT '软删除：0=正常，1=已删除',
    version         INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_audit_user_created (user_id, created_at),
    INDEX idx_audit_created (created_at),
    INDEX idx_audit_model_created (model_name, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI分析审计日志表';
```

tools_called 和 tool_results 从 TEXT 改为 JSON 类型。补充了 tokens_total 计算列（GENERATED ALWAYS AS，自动等于输入加输出 token 之和）、cost_cents（调用成本，单位分）和 status（调用状态）三个字段。tokens_total 作为 STORED 计算列可建索引，便于按成本排序和统计。索引 (user_id, created_at) 支撑用户维度的调用历史查询，(model_name, created_at) 支撑按模型统计成本趋势。本表保留 1 年后由清理任务删除，idx_audit_created 索引支撑按时间范围批量清理。

### 2.13 字段类型审查结论

对原报告 10 张表的字段类型做了一次完整审查，核心结论如下表。

| 审查项 | 原报告做法 | 优化后做法 | 理由 |
|--------|-----------|-----------|------|
| 时间戳字段 | VARCHAR(32) | DATETIME | 原生类型占用 5 字节（VARCHAR(19) 占 19 字节），时间范围查询和排序效率更高，生命周期清理依赖原生类型 |
| 日期字段 | VARCHAR(32) | DATE | 买入日期等只需日期精度，DATE 占 3 字节更紧凑 |
| 价格金额字段 | DECIMAL(12,2) | DECIMAL(20,4) | 与 Tushare 行情表精度一致，避免精度截断，20 位整数覆盖极端高价场景 |
| JSON 结构字段 | TEXT | JSON | MySQL 5.7+ 原生 JSON 支持字段级查询和自动校验 |
| 布尔字段 | TINYINT | TINYINT（保持） | 符合规范，无需调整 |
| 软删除字段 | 缺失 | is_deleted TINYINT | 用户删除操作不应物理删除，便于恢复和审计 |
| 乐观锁字段 | 缺失 | version INT | 并发更新场景防止覆盖更新 |
| 更新时间 | 部分表缺失 | 全表补充 updated_at | 审计追溯需记录最后修改时间 |

需要说明的是，Tushare 业务表（ERP 管理）仍保留 VARCHAR(8) 存储 trade_date，这是刻意为之的设计选择：Tushare API 返回的日期格式就是 yyyyMMdd 字符串，保留原始格式避免采集时的类型转换开销，且 Tushare 表是按时间序列查询为主，VARCHAR(8) 配合联合索引能高效服务 ts_code + trade_date 查询。两类表的类型差异体现了分层设计的思路：采集层保留原始格式，应用层使用原生类型。

---

## 三、索引设计

### 3.1 索引设计原则

C 端用户业务表的索引设计遵循四条原则。**最左前缀匹配**要求联合索引的字段顺序按查询条件的出现频率和区分度从高到低排列，例如 (user_id, is_deleted, created_at) 中 user_id 区分度最高放最左，created_at 用于排序放最后。**覆盖索引**优先将查询涉及的字段都包含在索引中，避免回表读取数据行，例如通知列表查询只需 user_id、is_read、created_at 三个字段，联合索引能完全覆盖。**避免冗余索引**要求不建被其他索引包含的单列索引，例如已有 (user_id, is_deleted, updated_at) 就不再单独建 (user_id) 索引。**控制索引数量**要求每张表索引不超过 5 个，因为索引越多写入越慢，C 端表虽然读多写少但也不能无节制加索引。

所有用户业务表的索引都以 user_id 为首列，这是数据隔离的物理保障。查询用户数据时 MyBatis-Plus 的 QueryWrapper 强制追加 user_id 条件，索引以 user_id 开头确保查询走索引而非全表扫描。

### 3.2 各表索引方案

下表汇总 10 张表的索引设计方案，标注每个索引服务的查询场景。

| 表名 | 索引名 | 索引字段 | 服务的查询场景 |
|------|--------|----------|---------------|
| chat_conversation | idx_conv_user_deleted_updated | (user_id, is_deleted, updated_at) | 会话列表按最近更新排序 |
| chat_conversation | idx_conv_user_status | (user_id, status) | 按状态筛选会话 |
| chat_message | idx_msg_conv_created | (conversation_id, created_at) | 加载会话历史消息按时间排序 |
| chat_message | idx_msg_created | (created_at) | 生命周期清理按时间范围扫描 |
| user_watchlist | uk_watchlist_user_code_group | UNIQUE(user_id, ts_code, group_name) | 防止重复添加同一股票 |
| user_watchlist | idx_watchlist_user_group_sort | (user_id, group_name, sort_order) | 按分组展示自选股并排序 |
| user_watchlist | idx_watchlist_code | (ts_code) | 按股票查所有关注用户 |
| user_portfolio | idx_portfolio_user_status | (user_id, status) | 展示用户当前持仓 |
| user_portfolio | idx_portfolio_code | (ts_code) | 按股票查持仓 |
| monitor_rule | idx_monitor_user_enabled | (user_id, enabled, is_deleted) | 用户规则列表 |
| monitor_rule | idx_monitor_scan | (ts_code, enabled, rule_type) | 监控引擎按股票扫描规则 |
| user_task | idx_task_user_enabled | (user_id, enabled, is_deleted) | 用户任务列表 |
| user_task | idx_task_schedule | (next_run_at, enabled) | 调度器扫描到期任务 |
| user_task_log | idx_log_task_run | (task_id, run_at) | 任务执行历史 |
| user_task_log | idx_log_run_at | (run_at) | 生命周期清理 |
| analysis_report | idx_report_user_type_fav | (user_id, report_type, is_favorited, is_deleted) | 按类型和收藏筛选报告 |
| analysis_report | idx_report_user_created | (user_id, is_deleted, created_at) | 全部报告按时间排序 |
| analysis_report | idx_report_code_created | (ts_code, created_at) | 某股票的历史报告 |
| analysis_report | idx_report_industry | (industry) | 按行业筛选报告 |
| push_notification | idx_notif_user_read_created | (user_id, is_read, is_deleted, created_at) | 通知列表按未读和时间排序 |
| push_notification | idx_notif_created | (created_at) | 生命周期清理 |
| ai_analysis_audit | idx_audit_user_created | (user_id, created_at) | 用户调用历史 |
| ai_analysis_audit | idx_audit_created | (created_at) | 生命周期清理 |
| ai_analysis_audit | idx_audit_model_created | (model_name, created_at) | 按模型统计成本 |

### 3.3 索引审查与优化总结

原报告的索引设计存在三个共性问题，本次优化逐一修正。第一，**单列索引冗余**：原报告多张表建了 (user_id) 单列索引，但所有业务查询都带 user_id 且配合其他条件，单列索引被联合索引包含后纯属浪费写入开销，本次全部替换为联合索引。第二，**缺少排序字段**：原报告的索引只覆盖 WHERE 条件不覆盖 ORDER BY，例如 chat_message 的 (conversation_id) 索引无法避免按 created_at 排序时的 filesort，本次补全为 (conversation_id, created_at) 联合索引。第三，**缺少生命周期索引**：原报告未考虑数据清理场景，push_notification 和 ai_analysis_audit 等需要定期清理的表缺少 created_at 索引，清理任务只能全表扫描，本次补充时间索引。

monitor_rule 表的索引设计体现了"读多写少"场景的特殊考量。盯盘引擎每分钟扫描所有启用规则，按 ts_code 分组检查，因此 (ts_code, enabled, rule_type) 索引让扫描按股票维度命中。而用户管理规则时按 user_id 查询，因此 (user_id, enabled, is_deleted) 索引服务管理页面。两个索引分别服务两个完全不同的访问模式，互不替代。

---

## 四、Tushare 业务表概述

### 4.1 表分类与用途

Tushare 业务表由 B 端 ERP 系统通过六层数据架构采集和管理，C 端应用只读查询。这些表参考 stock-pulse 的 schema 设计，共 30+ 张，按数据类别分为五大类。

| 数据类别 | 表数量 | 核心表 | 用途 |
|----------|--------|--------|------|
| **股票基础信息** | 6 张 | stock_basic / trade_cal / adj_factor / dividend / stock_namechange / stock_suspend_d | 股票元数据、交易日历、复权因子、分红送股、ST 变更、停复牌 |
| **行情数据** | 4 张 | daily_quote / daily_basic / index_daily / index_basic | 个股日线 OHLCV、每日估值指标、指数日线行情、指数基础信息 |
| **财务数据** | 7 张 | income / balancesheet / cashflow / fina_indicator / forecast / express / stk_holdernumber | 利润表、资产负债表、现金流量表、财务指标、业绩预告、业绩快报、股东人数 |
| **资金数据** | 6 张 | stock_moneyflow / hk_hold / top_list / top_inst / block_trade / margin_detail | 个股资金流向、沪深港通持股、龙虎榜、营业部席位、大宗交易、融资融券明细 |
| **事件数据** | 4 张 | stk_holdertrade / stock_stk_limit / sw_industry / sw_industry_member | 股东增减持、涨跌停价、申万行业分类、行业成分股 |
| **治理数据** | 2 张 | data_governance_metric / data_pull_log | 数据质量检测历史、数据拉取日志 |

此外还有 index_weight（指数成分股权重）和 margin（融资融券汇总）两张表，分别归入行情和资金类别。所有表的 DDL 在 ERP 项目中独立维护，C 端应用通过 MyBatis-Plus 的 Mapper 只读查询。

### 4.2 核心表说明

**股票基础信息类**是所有查询的元数据底座。stock_basic 存储全部 A 股的代码、名称、行业、上市状态等信息，C 端的股票卡片展示和 ts_code 关联查询都依赖此表。trade_cal 记录交易日历，is_open 字段标识是否交易日，C 端判断"今天是否开盘"直接查此表。stock_namechange 和 stock_suspend_d 服务于数据清洗层，采集时据此剔除 ST 和停牌标的。

**行情数据类**是 C 端最高频查询的表。daily_quote 存储个股日线 OHLCV，主键为 (ts_code, trade_date)，C 端的 K 线图、行情卡片均从此表读取。daily_basic 存储每日估值指标（PE/PB/股息率/换手率/市值），是 PE 分位图、估值卡片的数据来源。index_daily 存储大盘指数（上证指数、沪深 300 等）日线行情，服务"今天大盘怎么样"类查询。

**财务数据类**是深度研究报告的核心数据源。income、balancesheet、cashflow 三张表存储财务三表的完整字段，每张表 50-80 个字段，按 (ts_code, end_date, report_type) 唯一约束。fina_indicator 存储 ROE、ROA、毛利率、资产负债率等衍生指标，是质量类因子的数据来源。forecast 和 express 分别存储业绩预告和快报，服务于"茅台发财报了"类事件触发。

**资金数据类**服务资金面分析。stock_moneyflow 存储个股资金流向（大单/中单/小单/特大单买卖金额），是资金流向图卡片的数据来源。hk_hold 存储沪深港通持股明细，服务北向资金监控。top_list 和 top_inst 存储龙虎榜个股和营业部席位明细，是龙虎榜卡片的数据来源。

### 4.3 C 端只读权限控制

C 端应用对 Tushare 业务表的访问通过三层控制确保只读安全。第一层是**数据库账号隔离**：C 端应用使用独立的 MySQL 账号，该账号对 Tushare 业务表只授予 SELECT 权限，不授予 INSERT/UPDATE/DELETE 权限，从数据库层面杜绝误写。第二层是**代码层约束**：C 端的 MyBatis-Plus Mapper 对 Tushare 表只暴露 select 方法，不继承 IService 的 save/update/remove 等写方法，代码评审时检查不出现写操作调用。第三层是**逻辑外键引用**：C 端用户业务表（如 user_watchlist、analysis_report）通过 ts_code 字段引用 Tushare 表的 stock_basic，但不建立物理外键约束，避免跨写权限域的外键依赖。

```
MySQL 账号权限矩阵：
┌─────────────────┬───────────────────┬───────────────────┐
│                 │ Tushare 业务表     │ C 端用户业务表     │
│                 │ (30+张)           │ (10张)            │
├─────────────────┼───────────────────┼───────────────────┤
│ ERP 账号(:8081) │ SELECT/INSERT/    │ 无权限            │
│                 │ UPDATE/DELETE     │                   │
├─────────────────┼───────────────────┼───────────────────┤
│ C端账号(:8080)  │ SELECT（只读）    │ SELECT/INSERT/    │
│                 │                   │ UPDATE/DELETE    │
└─────────────────┴───────────────────┴───────────────────┘
```

ERP 账号对 C 端用户业务表无任何权限，C 端账号对 Tushare 业务表只有 SELECT 权限。两个账号的权限完全隔离，即使 C 端应用出现 SQL 注入漏洞，攻击者也无法篡改 Tushare 数据，只能读取公开的行情和财务数据。

---

## 五、Redis 数据规划

### 5.1 数据结构选型

Redis Stack 在平台中承担四类职责，每类根据访问模式选择最合适的 Redis 数据结构。

| 用途 | 数据结构 | Key 模式 | 说明 |
|------|----------|----------|------|
| **会话令牌** | String | `session:{token}` | 存 userId，TTL 30 分钟，每次请求续期 |
| **ChatMemory 窗口** | List | `chatmem:{conversationId}` | 存储最近 20 条消息，LPUSH 写入 LRANGE 读取 |
| **用户偏好** | Hash | `pref:{userId}` | 存关注板块、估值偏好等字段，跨会话生效 |
| **实时行情缓存** | Hash | `quote:{ts_code}:{trade_date}` | 存 open/high/low/close/pe/pb 等字段，TTL 当日 |
| **估值指标缓存** | String(JSON) | `val:{ts_code}:{trade_date}` | 存 PE/PB 分位等计算结果，TTL 1 小时 |
| **盯盘事件队列** | Stream | `stream:monitor:events` | 触发事件入流，消费者组消费后推送 |
| **任务结果队列** | Stream | `stream:task:results` | 定时任务完成结果入流，推送服务消费 |
| **向量索引** | RediSearch | `idx_methodology` / `idx_reports` / `idx_company` | 三个 RAG 知识库的向量索引 |

会话令牌用 String 是因为只需 token 到 userId 的简单映射，每次请求 GET 一次并 EXPIRE 续期。ChatMemory 窗口用 List 是因为消息天然有序，LPUSH 写入最新消息到头部，LRANGE 0 19 读取最近 20 条，超出窗口的用 LTRIM 裁剪，操作语义清晰。用户偏好用 Hash 是因为偏好是多字段结构（关注板块、估值偏好、风险偏好等），HMGET 一次取出全部字段，HSET 单独更新某个字段。

实时行情缓存用 Hash 而非 String(JSON)，因为行情数据有多个独立字段（开高低收、PE、PB、成交量等），Hash 支持只读取需要的字段（HGET close pe）而不反序列化整个 JSON。盯盘事件和任务结果用 Stream 而非 List，因为 Stream 提供消费者组（consumer group）和消息确认（XACK）机制：推送服务消费消息后确认，若推送服务崩溃重启后能从 pending entries list 恢复未确认的消息，不会丢失通知。

### 5.2 Key 命名规范

Redis Key 采用层次化命名，格式为 `业务域:实体:标识`，用冒号分隔层级。这种命名让 Key 在 Redis CLI 中用 SCAN 浏览时层次清晰，也便于用 KEYS/SCAN 按前缀批量管理。

```
前缀规范：
session:{token}                    会话令牌
chatmem:{conversationId}           对话内存窗口
pref:{userId}                      用户偏好
quote:{ts_code}:{trade_date}       实时行情（如 quote:600519.SH:20260806）
val:{ts_code}:{trade_date}         估值指标
stream:monitor:events              盯盘事件流
stream:task:results                任务结果流
idx_methodology                    方法论向量索引
idx_reports                        历史报告向量索引
idx_company                        公司基本面向量索引
```

Key 中的标识使用业务 ID 而非自增主键。例如会话令牌用 token（UUID）而非 user_id，因为同一用户可能多端登录产生多个会话。行情缓存的 Key 带 trade_date 是为了实现"当日有效"的 TTL 策略：次日 trade_date 变化后旧 Key 自然不再命中，无需主动失效。

### 5.3 缓存策略与防护

缓存策略围绕新鲜度和性能取平衡。实时行情缓存的 TTL 设为当日有效（Key 带 trade_date，当日不主动过期但次日自然失效），配合 ERP 每日 16:00 采集节奏，保证 C 端在盘后能读到当日最新行情。估值指标变化频率低，TTL 设为 1 小时，在新鲜度和缓存命中率之间取平衡。会话令牌 TTL 30 分钟，每次请求自动续期，用户活跃期间不会掉线。

针对三类缓存风险，平台采取对应的防护措施。**缓存穿透**（查询不存在的数据，每次都回源数据库）通过空值缓存防护：查询数据库未命中时，将空结果以短 TTL（60 秒）缓存到 Redis，后续相同查询直接命中空值缓存，避免持续打数据库。**缓存击穿**（热点 Key 过期瞬间大量请求同时回源）通过互斥锁防护：缓存未命中时用 SETNX 抢锁，抢到锁的请求回源数据库并回填缓存，未抢到锁的请求短暂等待后重试读缓存。**缓存雪崩**（大量 Key 同时过期导致数据库瞬时压力激增）通过 TTL 随机抖动防护：在基础 TTL 上叠加 0-300 秒的随机偏移，让 Key 的过期时间分散开，避免集体失效。

```
缓存查询流程：
请求 -> Caffeine 本地缓存 -> 命中则返回
                            -> 未命中 -> Redis -> 命中则返回并回填 Caffeine
                                                -> 未命中 -> MySQL -> 命中则回填 Redis + Caffeine
                                                                -> 未命中 -> 空值缓存(60s TTL)
```

Caffeine 本地缓存作为第一级缓存，部署在 C 端后端进程内，容量有限但访问零网络开销，用于缓存最热门的行情数据（如当日大盘指数、热门股票实时价）。Redis 作为第二级分布式缓存，所有 C 端实例共享。多级缓存让"多用户同时查茅台行情"这一高频场景只命中一次数据库。

### 5.4 RediSearch 向量索引

RAG 检索增强依赖 Redis Stack 的 RediSearch 模块，维护三个向量索引，分别服务不同的检索场景。

**投资方法论库**（idx_methodology）存储估值方法、技术分析方法、财务分析框架等方法论文档。当 AI 需要引用某套分析方法论时，先将用户查询向量化，再在索引中做相似度检索，将匹配的方法论文档注入对话上下文。**历史报告库**（idx_reports）存储用户过往生成的分析报告的向量化摘要，当用户问"上次分析的茅台现在怎么样了"时，检索历史报告做对比分析。**公司基本面库**（idx_company）基于 Tushare 财务数据生成的公司摘要向量，支撑"帮我了解这家公司"类查询。

向量索引通过 RediSearch 的 FT.CREATE 命令创建，使用 HNSW（分层导航小世界图）算法做近似最近邻检索，在召回率和检索速度之间取平衡。每个索引定义一个 VECTOR 类型字段，维度与嵌入模型输出维度一致（通义千问的 text-embedding-v2 输出 1536 维），距离度量用 COSINE（余弦相似度）。

```
RediSearch 索引创建示例（方法论库）：
FT.CREATE idx_methodology ON HASH
  PREFIX 1 doc:methodology:
  SCHEMA
    title TEXT
    category TAG
    content TEXT
    embedding VECTOR HNSW 6
      TYPE FLOAT32 DIM 1536
      DISTANCE_METRIC COSINE
      M 16 EF_CONSTRUCTION 200

检索流程：
1. 用户查询文本 -> 通义千问 text-embedding-v2 -> 1536维向量
2. FT.SEARCH idx_methodology "*=>[K 3 VECTOR embedding $query_vec]"
3. 返回 Top-3 相似文档，注入 AI 对话上下文
```

向量数据以 Hash 结构存储，Key 前缀为 doc:methodology:{id}，包含 title、category、content 和 embedding 四个字段。检索时先通过嵌入模型将用户查询转为向量，再用 FT.SEARCH 命令做 K 近邻检索，返回余弦相似度最高的 K 篇文档。检索结果作为上下文注入 ChatClient，让 AI 引用方法论或历史报告时"有据可依"，降低幻觉风险。

---

## 六、数据生命周期管理

### 6.1 数据保留策略

C 端用户业务表的数据量随用户活跃度持续增长，不同类型的业务数据有不同的保留价值，需要差异化制定保留策略。

| 数据类型 | 所在表 | 保留策略 | 理由 |
|----------|--------|----------|------|
| 对话消息 | chat_message | 热数据 6 个月，冷归档更早 | 消息量大，6 个月内覆盖绝大多数回溯需求 |
| 分析报告 | analysis_report | 永久保留 | 报告是用户核心资产，报告中心的核心价值就是可回溯 |
| 推送通知 | push_notification | 3 个月后清理 | 通知是即时消费内容，3 个月后无回溯价值 |
| 审计日志 | ai_analysis_audit | 1 年保留 | 满足成本审计和问题追溯需求，1 年足够 |
| 任务执行日志 | user_task_log | 6 个月保留 | 配合任务执行历史查看，6 个月足够 |
| 盯盘规则 | monitor_rule | 随用户操作保留 | 用户停用即可，不主动清理 |
| 定时任务 | user_task | 随用户操作保留 | 用户停用即可，不主动清理 |
| 自选股/持仓 | user_watchlist / user_portfolio | 永久保留 | 用户资产，不清理 |

对话消息是数据量增长最快的表，单个活跃用户每月可能产生数千条消息。保留 6 个月的热数据能满足绝大多数对话回溯需求（用户很少翻看半年前的对话），超过 6 个月的消息归档到冷存储而非直接删除，因为审计场景可能需要更长的追溯窗口。分析报告永久保留，因为报告中心的核心理念是"报告资产化"，用户可能随时回溯一年前的报告做对比。

### 6.2 冷热数据分离

数据量大的表采用冷热分离策略，将近期热数据和历史冷数据分层存储，让热查询只扫描小范围数据。

```
冷热分离架构：

MySQL 热库（SSD）                  MySQL 冷库 / 对象存储（HDD/归档）
┌─────────────────────┐            ┌─────────────────────────┐
│ 近 6 个月数据        │   归档     │ 6 个月以上数据           │
│ chat_message         │ ───────>  │ chat_message_archive    │
│ push_notification    │   迁移     │ (压缩表，只读查询)       │
│ user_task_log        │           │                         │
│ ai_analysis_audit    │           │ Tushare 历史行情         │
└─────────────────────┘            │ daily_quote（2年前）     │
                                   │ (按年分区，旧分区压缩)    │
                                   └─────────────────────────┘
```

对于 Tushare 业务表中的行情数据（daily_quote、daily_basic 等），按 trade_date 做范围分区，每年一个分区。近 2 年的分区存在 SSD 上保证查询性能，2 年以上的分区可以压缩存储或迁移到冷库。用户查询历史 K 线时，近 2 年的数据走热分区毫秒级返回，更早的历史数据走冷分区虽有延迟但属于低频场景。

对于 C 端用户业务表中的 chat_message 和 ai_analysis_audit，采用归档表方案：超过保留期限的数据从主表迁移到归档表（如 chat_message_archive），归档表使用 ROW_FORMAT=COMPRESSED 压缩存储，占用空间减少 50% 以上。归档表只支持只读查询，用户翻看半年前对话时走归档表，不影响主表的查询性能。迁移操作在业务低峰期（凌晨）执行，按 user_id 分批迁移，每批 500 条，避免长事务锁表。

### 6.3 数据清理任务设计

数据清理和归档通过 Quartz 定时任务执行，按数据类型设置不同的调度频率和清理逻辑。

| 清理任务 | 调度频率 | 清理逻辑 | 保护措施 |
|----------|----------|----------|----------|
| 推送通知清理 | 每日凌晨 3:00 | 删除 is_deleted=1 且 created_at < 3 个月前的通知 | 物理删除已软删数据 |
| 审计日志清理 | 每月 1 日凌晨 3:00 | 删除 created_at < 1 年前的审计日志 | 先归档再删除 |
| 任务日志清理 | 每月 1 日凌晨 3:00 | 删除 run_at < 6 个月前的任务日志 | 直接删除 |
| 对话消息归档 | 每周日凌晨 3:00 | 迁移 created_at < 6 个月前的消息到归档表 | 分批迁移，每批 500 条 |
| Tushare 旧分区压缩 | 每年 1 月 1 日 | 压缩 2 年前的行情分区 | ALTER TABLE ... REORGANIZE PARTITION |
| Redis 过期 Key 扫描 | 每日凌晨 4:00 | 扫描空值缓存等短 TTL Key | Redis 自动过期 + 主动 SCAN 清理 |

每个清理任务都遵循三个保护原则。**分批执行**要求每次清理限制行数（如每次删除 1000 行），循环执行直到清理完毕，避免单次删除数百万行导致长时间锁表。**低峰执行**要求所有清理任务调度在凌晨 3:00-4:00 的业务低峰期，此时用户活跃度最低，清理对线上影响最小。**先归档后删除**要求对有审计价值的数据（如审计日志）先迁移到归档表再从主表删除，确保数据可恢复。

清理任务的执行结果记录在 data_pull_log 表中（复用 ERP 的日志基础设施），记录清理的表名、清理行数、耗时和状态。运维人员通过 ERP 管理页面监控清理任务的执行情况，发现清理失败时及时排查。

---

## 七、数据安全与权限

### 7.1 用户数据隔离

C 端用户业务表的数据隔离通过 user_id 维度过滤实现，确保用户只能访问自己的数据。所有用户业务表的索引都以 user_id 为首列，这是物理层面的隔离保障。应用层面的隔离通过 MyBatis-Plus 的 QueryWrapper 强制追加 user_id 条件实现：每个查询方法在构建 QueryWrapper 时必须 `.eq("user_id", currentUserId)`，代码评审时检查这一条件不可遗漏。

```
数据隔离的三个层面：

1. 索引层：所有用户表索引以 user_id 为首列
   -> 查询 WHERE user_id = ? 必走索引，不会全表扫描泄露他人数据

2. 应用层：QueryWrapper 强制追加 user_id 条件
   -> Service 层封装 getUserId() 注入，Controller 层不接受前端传入的 user_id

3. 会话层：每次请求校验 session token
   -> 从 Redis 取 session:{token} 得到 userId，未登录请求返回 401
```

Function Calling 工具调用时也携带当前 user_id，查询用户业务数据时强制按 user_id 过滤。例如 AI 调用 queryWatchlist 工具时，工具内部执行 `WHERE user_id = ? AND is_deleted = 0`，AI 无法通过构造参数越权查询其他用户的自选股。user_id 来源始终是服务端会话上下文，前端传入的任何 user_id 参数都被忽略。

### 7.2 敏感数据处理

平台涉及的敏感数据主要是用户手机号和密码，分别采用脱敏和加密处理。

**密码加密**使用 BCrypt 加盐哈希算法，密码不可逆存储，即使数据库泄露也无法还原明文。BCrypt 的 cost 参数设为 10（与 stock-pulse 一致），每次哈希计算约 100 毫秒，在安全性和性能之间取平衡。用户登录时前端传入明文密码（HTTPS 加密传输），后端用 BCrypt.matches() 校验，校验通过后生成 session token 存入 Redis。

**手机号脱敏**在展示层处理，数据库存储完整手机号（用于短信验证码登录），但 API 返回和前端展示时脱敏为 138****1234 格式。脱敏逻辑在 DTO 序列化层统一处理，通过自定义 Jackson 序列化器对 phone 字段自动脱敏，避免每个接口手动处理。脱敏规则：保留前 3 位和后 4 位，中间用 4 个星号替代。

```
敏感数据处理流程：

注册/登录：
  前端 -> HTTPS -> 后端接收明文手机号和密码
  -> 密码 BCrypt 哈希存入 sys_user.password
  -> 手机号完整存入 sys_user.phone

展示/查询：
  后端查询 sys_user -> DTO 序列化
  -> 自定义 Jackson 序列化器对 phone 字段脱敏
  -> 前端收到 138****1234

短信验证码：
  后端读取完整手机号 -> 调用短信服务发送验证码
  -> 验证码存入 Redis（code:{phone}，TTL 5 分钟）
```

### 7.3 数据备份策略

数据备份采用全量与增量结合的策略，覆盖 MySQL 和 Redis 两类存储。

**MySQL 备份**通过 mysqldump 或 Percona XtraBackup 执行，全量备份每周一次（周日凌晨 2:00），增量备份通过 binlog 实时同步。备份文件存储在独立服务器或对象存储上，保留最近 4 周的全量备份和 7 天的 binlog。恢复时先恢复最近一次全量备份，再重放 binlog 到指定时间点，实现时间点恢复（PITR）。

**Redis 备份**通过 RDB 快照和 AOF 日志结合。RDB 快照每 6 小时执行一次（BGSAVE），AOF 日志实时追加写入。RDB 用于全量恢复，AOF 用于增量恢复。由于 Redis 数据主要是缓存（可从 MySQL 重建）和会话（丢失后用户重新登录即可），备份优先级低于 MySQL，恢复时以 MySQL 为准重建缓存。

| 备份对象 | 备份方式 | 频率 | 保留期 | 恢复方式 |
|----------|----------|------|--------|----------|
| MySQL 全量 | XtraBackup / mysqldump | 每周日凌晨 2:00 | 4 周 | 恢复全量 + 重放 binlog |
| MySQL binlog | 实时同步 | 实时 | 7 天 | 时间点恢复（PITR） |
| Redis RDB | BGSAVE 快照 | 每 6 小时 | 3 份 | 恢复 RDB + 重放 AOF |
| Redis AOF | 实时追加 | 实时 | 7 天 | 重放 AOF 日志 |

灾备方案上，初期采用单机房主从架构（MySQL 主从复制 + Redis 哨兵），主节点故障时从节点自动提升为主。当用户量增长到需要更高可用性时，可升级为跨机房主从或同城双活，但初期单机房主从已能满足 99.5% 的可用性目标。备份恢复演练每季度执行一次，验证备份文件的有效性和恢复流程的可靠性，避免"备份了但恢复不了"的陷阱。
