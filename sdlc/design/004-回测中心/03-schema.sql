-- ============================================================
-- 回测中心（模块 004）建表 SQL
-- ============================================================
-- 设计依据：
--   PRD:    sdlc/prd/004-回测中心/004-回测中心PRD.md
--   DB 设计: sdlc/design/004-回测中心/02-db-design.md
--   原型图:  sdlc/prd/004-回测中心/prototype/*.html
--
-- 执行目标：SQLite（与现有 schema.sql 同数据库，单文件 + WAL）
-- 执行方式：追加到 stock-watcher/src/main/resources/schema.sql 末尾，或独立执行
-- 幂等保证：所有 CREATE TABLE 使用 IF NOT EXISTS
-- 字段命名：snake_case（与现有 daily_quote / stock_basic 等表风格一致）
-- 数据类型：INTEGER / TEXT / REAL（SQLite 原生类型，不使用 MySQL 专有类型）
--
-- 复用现有表（本文件不重复定义，仅使用其数据）：
--   sys_user       ← 用户表，user_id 关联实现按用户隔离
--   quant_strategy ← 策略表（由策略管理模块维护），本模块只读 + 有限回写参数
--   daily_quote    ← 日线行情，构建 Python 入参
--   stock_basic    ← 股票基础信息，代码有效性校验 + 名称映射
--   trade_cal      ← 交易日历，日期范围有效性校验
--   adj_factor     ← 复权因子，数据预处理使用
-- ============================================================

-- ------------------------------------------------------------
-- 表 1：quant_backtest — 回测任务主记录
-- 对应原型图：
--   backtest-config.html  表单提交后写入一条 RUNNING 记录
--   backtest-list.html    列表页的每一行数据来源
--
-- 设计说明：
--   - 字段与 config.html 表单一一对应（策略选择 → strategy_id，
--     初始资金 → initial_cash，调仓频率 → rebalance_frequency_days 等）
--   - 核心指标（total_return_pct / sharpe_ratio 等）在主表冗余存储，
--     供 list.html 列表页快速展示，免去解析大 JSON 或 JOIN
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quant_backtest (
    id                          INTEGER  PRIMARY KEY AUTOINCREMENT,

    user_id                     INTEGER  NOT NULL,                  -- 关联 sys_user.id（用户隔离）
    strategy_id                 INTEGER  NOT NULL,                  -- 关联 quant_strategy.id（config.html 策略选择器）
    strategy_version            INTEGER  NOT NULL  DEFAULT 1,       -- 策略版本号
    task_id                     TEXT     NOT NULL  UNIQUE,          -- Python 计算任务 UUID（PRD §3.1 通信协议）

    mode                        TEXT     NOT NULL  DEFAULT 'SINGLE'  -- SINGLE / GRID_SEARCH / WALK_FORWARD
                                   CHECK (mode IN ('SINGLE', 'GRID_SEARCH', 'WALK_FORWARD')),

    start_date                  TEXT     NOT NULL,                  -- 回测开始日期 YYYY-MM-DD（config.html 日期 input）
    end_date                    TEXT     NOT NULL,                  -- 回测结束日期 YYYY-MM-DD
    universe_type               TEXT     NOT NULL                    -- INDEX_300 沪深300 / INDEX_500 中证500 / CUSTOM 自定义
                                   CHECK (universe_type IN ('INDEX_300', 'INDEX_500', 'CUSTOM')),
    universe_codes              TEXT,                                -- JSON 数组，CUSTOM 模式的股票代码
                                                                     --   例：["600519.SH", "000001.SZ"]
    universe_count              INTEGER,                             -- 实际股票数量（冗余，list.html 快速展示）

    initial_cash                REAL     NOT NULL,                  -- 初始资金（人民币，config.html 表单字段）
    commission_pct              REAL     NOT NULL  DEFAULT 0.0003,   -- 佣金率（默认万三 = 0.03%）
    slippage_pct                REAL     NOT NULL  DEFAULT 0.001,    -- 滑点（默认 0.1%）
    rebalance_frequency_days    INTEGER  NOT NULL  DEFAULT 5,        -- 调仓频率（交易日，config.html 「调仓频率(交易日)」）
    max_positions               INTEGER,                             -- 最大持仓数（NULL 时使用策略默认值）
    max_single_position_pct     REAL,                                -- 单票最大仓位比例（0.10 = 10%）

    benchmark_enabled           INTEGER  NOT NULL  DEFAULT 1         -- 是否启用基准对比（1=是，0=否，SQLite 无 BOOLEAN）
                                   CHECK (benchmark_enabled IN (0, 1)),

    param_grid_json             TEXT,                                -- JSON：网格搜索/WF 的参数范围定义
                                                                     --   例：{"buy_rules.conditions[0].left.params.period": [5, 10, 15, 20, 30],
                                                                     --         "sell_rules.stop_loss.percent": [0.05, 0.07, 0.09, 0.11, 0.13]}
    optimization_metric         TEXT,                                -- 优化目标指标（GRID_SEARCH/WALK_FORWARD 必填）
                                                                     --   sharpe_ratio / total_return_pct / win_rate / calmar_ratio
    walk_forward_json           TEXT,                                -- JSON：WF 专属配置
                                                                     --   例：{"trainingWindowDays": 252, "validationWindowDays": 63, "stepDays": 63}

    status                      TEXT     NOT NULL  DEFAULT 'RUNNING' -- RUNNING / SUCCESS / FAILED（list.html 状态标签）
                                   CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    error_message               TEXT,                                -- 失败原因（status=FAILED 时非空）

    -- 核心指标（列表页直接展示，避免解析大 JSON）
    total_return_pct            REAL,                                -- 总收益率（0.245 = +24.5%）
    sharpe_ratio                REAL,                                -- 夏普比率
    max_drawdown_pct            REAL,                                -- 最大回撤（-0.123 = -12.3%）
    win_rate                    REAL,                                -- 胜率（0~1，0.58 = 58%）
    trades_count                INTEGER,                             -- 交易总次数（report.html 「共 N 笔交易」）

    compute_duration_ms         INTEGER,                             -- Python 计算耗时（毫秒）

    created_at                  TEXT     NOT NULL,                  -- 创建时间（ISO 格式：YYYY-MM-DD HH:MM:SS）
    updated_at                  TEXT,                                -- 更新时间
    completed_at                TEXT                                 -- 完成时间（成功/失败时写入）
);

-- ------------------------------------------------------------
-- 表 2：quant_backtest_report — 单次回测详细数据（1:1 关联 quant_backtest）
-- 对应原型图：backtest-report.html
--
-- 设计说明：
--   - 与主表 1:1 关联，backtest_id 同时作为主键（节省一个独立自增 ID）
--   - 各 JSON 字段直接映射 report.html 中的各图表区块
--   - 由于 JSON 字段可能较大（equity_curve_json + trades_json 可达 100KB+），
--     单独成表避免主表 IO 负担（list.html 只扫主表，报告页才 JOIN 本大字段表）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quant_backtest_report (
    backtest_id             INTEGER  PRIMARY KEY,                    -- 主键 = 外键，1:1 关联 quant_backtest.id

    equity_curve_json       TEXT     NOT NULL,                       -- 策略净值曲线 → report.html 净值曲线双折线图（策略线）
                                                                     --   结构：[{"date": "2024-01-02", "value": 1000000}, ...]
    benchmark_curve_json    TEXT,                                    -- 基准（沪深300）净值曲线 → 双折线图（基准线）
                                                                     --   benchmark_enabled=0 时为 NULL
    drawdown_curve_json     TEXT     NOT NULL,                       -- 回撤曲线 → report.html 回撤曲线面积图
                                                                     --   结构：[{"date": "2024-01-02", "drawdownPct": 0.0}, ...]
    trades_json             TEXT     NOT NULL,                       -- 交易流水 → report.html 交易流水表格
                                                                     --   结构：[{"date", "tsCode", "action": "BUY|SELL",
                                                                     --           "price", "qty", "amount", "pnl", "reason"}, ...]
    positions_json          TEXT,                                    -- 调仓日持仓快照 → 持仓变化图
                                                                     --   结构：[{"date", "positions": [{"tsCode", "shares", ...}]}]
    metrics_json            TEXT     NOT NULL,                       -- 全量指标 → report.html 详细指标表
                                                                     --   包含：年化收益率/波动率/索提诺/卡尔马/盈亏比/
                                                                     --          最大单笔盈利/平均持仓天数/基准收益率/超额收益率
    monthly_returns_json    TEXT,                                    -- 月度收益率矩阵 → report.html 月度热力图
                                                                     --   结构：{"2024-01": 0.021, "2024-02": 0.035, ...}

    created_at              TEXT     NOT NULL                        -- 创建时间
);

-- ------------------------------------------------------------
-- 表 3：quant_backtest_grid_result — 参数优化每组参数的指标
-- 对应原型图：backtest-grid.html
--
-- 设计说明：
--   - 与主表 1:N 关联：每一次参数优化任务产生 100~1000 条记录
--   - combination_rank 按 optimization_metric 值从高到低排序，1=最优
--   - 指标字段与主表保持一致（total_return_pct / sharpe_ratio / ...），
--     便于 grid.html 表格直接展示，无需解析 param_values_json
--   - is_best 冗余标记最优组合，便于快速查询
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quant_backtest_grid_result (
    id                  INTEGER  PRIMARY KEY AUTOINCREMENT,
    backtest_id         INTEGER  NOT NULL,                           -- 关联 quant_backtest.id

    combination_rank    INTEGER  NOT NULL,                           -- 排名（1=最优）→ grid.html 表格第一列
    param_values_json   TEXT     NOT NULL,                           -- 参数值 JSON → 表格参数列
                                                                     --   例：{"buy_rules.conditions[0].left.params.period": 10,
                                                                     --         "sell_rules.stop_loss.percent": 0.08, ...}
    metric_value        REAL     NOT NULL,                           -- 目标指标值 → grid.html 高亮列 + 散点图 Y 轴

    total_return_pct    REAL,                                        -- 总收益率 → 表格列
    sharpe_ratio        REAL,                                        -- 夏普比率 → 表格列
    max_drawdown_pct    REAL,                                        -- 最大回撤 → 表格列
    win_rate            REAL,                                        -- 胜率 → 表格列
    trades_count        INTEGER,                                     -- 交易次数 → 表格列

    is_best             INTEGER  NOT NULL  DEFAULT 0                 -- 1=最优组合（grid.html 高亮行）
                                   CHECK (is_best IN (0, 1)),

    created_at          TEXT     NOT NULL,                           -- 创建时间

    UNIQUE (backtest_id, combination_rank)                           -- 同一任务内排名唯一
);

-- ------------------------------------------------------------
-- 表 4：quant_backtest_wf_result — Walk-forward 分段结果
-- 对应原型图：backtest-walk-forward.html
--
-- 设计说明：
--   - 与主表 1:N 关联：每一次 Walk-forward 任务产生 5~20 条记录
--   - segment_index 从 0 开始递增，代表时间轴上的分段顺序
--   - 每段记录包含「训练期」和「验证期」两组数据：
--       best_params_json     → 该段训练期选出的最优参数（用于评估参数稳定性）
--       in_sample_metrics    → 训练期的指标表现
--       out_sample_metrics   → 验证期的指标表现（过拟合检测核心关注点）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quant_backtest_wf_result (
    id                      INTEGER  PRIMARY KEY AUTOINCREMENT,
    backtest_id             INTEGER  NOT NULL,                       -- 关联 quant_backtest.id
    segment_index           INTEGER  NOT NULL,                       -- 分段序号（0 开始）→ walk-forward.html 时间轴第 N 段

    in_sample_start         TEXT     NOT NULL,                       -- 训练期开始日期 YYYY-MM-DD
    in_sample_end           TEXT     NOT NULL,                       -- 训练期结束日期
    out_sample_start        TEXT     NOT NULL,                       -- 验证期开始日期
    out_sample_end          TEXT     NOT NULL,                       -- 验证期结束日期

    best_params_json        TEXT     NOT NULL,                       -- 该段训练期选出的最优参数 → walk-forward.html 参数稳定性表
                                                                     --   结构与 grid_result.param_values_json 相同

    in_sample_metrics_json  TEXT     NOT NULL,                       -- 训练期指标 → 每段的「训练期表现」
                                                                     --   例：{"totalReturnPct": 0.25, "sharpeRatio": 1.8, ...}
    out_sample_metrics_json TEXT     NOT NULL,                       -- 验证期指标（过拟合检测核心）
                                                                     --   结构与训练期相同

    created_at              TEXT     NOT NULL,                       -- 创建时间

    UNIQUE (backtest_id, segment_index)                              -- 同一任务内分段序号唯一
);

-- ============================================================
-- 索引（所有索引均 IF NOT EXISTS，保证重复执行不报错）
-- ============================================================

-- quant_backtest 索引：列表页按 user+mode+status 过滤（最常用查询路径）
CREATE INDEX IF NOT EXISTS idx_bt_user_mode_status
    ON quant_backtest(user_id, mode, status);

-- quant_backtest 索引：策略维度回溯查询
CREATE INDEX IF NOT EXISTS idx_bt_strategy
    ON quant_backtest(strategy_id, strategy_version);

-- quant_backtest 索引：按创建时间倒序（列表页默认排序）
CREATE INDEX IF NOT EXISTS idx_bt_created
    ON quant_backtest(created_at DESC);

-- quant_backtest 索引：Python 计算完成后通过 task_id 反查主记录
CREATE INDEX IF NOT EXISTS idx_bt_task_id
    ON quant_backtest(task_id);

-- quant_backtest_grid_result 索引：按 backtest_id + rank 顺序查询
CREATE INDEX IF NOT EXISTS idx_grid_bt_rank
    ON quant_backtest_grid_result(backtest_id, combination_rank);

-- quant_backtest_grid_result 索引：按指标值排序 / 散点图
CREATE INDEX IF NOT EXISTS idx_grid_bt_metric
    ON quant_backtest_grid_result(backtest_id, metric_value DESC);

-- quant_backtest_wf_result 索引：按 backtest_id + segment_index 顺序查询
CREATE INDEX IF NOT EXISTS idx_wf_bt_segment
    ON quant_backtest_wf_result(backtest_id, segment_index);

-- ============================================================
-- 自检记录
-- ============================================================
-- [OK] 所有表/字段命名 snake_case，与现有 schema 风格一致
-- [OK] 仅使用 SQLite 原生类型：INTEGER / TEXT / REAL
-- [OK] BOOLEAN 语义用 INTEGER + CHECK(IN (0, 1)) 实现（SQLite 无 BOOLEAN）
-- [OK] 所有 CREATE TABLE 使用 IF NOT EXISTS，保证幂等性
-- [OK] 枚举约束用 CHECK 实现（SQLite 无 ENUM 类型）
-- [OK] 1:1 关系（quant_backtest_report）使用外键做主键，不新增独立自增 ID
-- [OK] 1:N 关系（grid_result / wf_result）使用自增主键 + backtest_id 外键
-- [OK] JSON 字段类型使用 TEXT（SQLite 无 JSON 原生类型），在应用层解析
-- [OK] 主表冗余核心指标字段，列表页查询不依赖大 JSON 解析
-- [OK] 与现有表（daily_quote / stock_basic / trade_cal / sys_user）不冲突
-- [OK] 原型图字段映射：config.html 表单字段 → main table 字段；
--                       report.html / grid.html / walk-forward.html 图表 → JSON 字段
