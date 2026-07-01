-- ================================================================
-- 003 策略管理 — schema.sql
-- 模块：策略主表 + 策略版本表（时间线）
-- 数据库：SQLite
-- 目标文件：stock-watcher/stock-watcher.sqlite
-- 说明：本文件只定义两张新表（quant_strategy / quant_strategy_version），
--       不修改任何已有表。所有日期时间字段为 TEXT（YYYY-MM-DD HH:mm:ss）。
-- ================================================================

-- 策略主表（最新规则快照 + 状态）
CREATE TABLE IF NOT EXISTS quant_strategy (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_key TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    category TEXT NOT NULL DEFAULT 'CUSTOM',
    status TEXT NOT NULL DEFAULT 'DRAFT',
    buy_rules TEXT NOT NULL,
    sell_rules TEXT NOT NULL,
    position_sizing TEXT,
    universe TEXT NOT NULL DEFAULT 'ALL',
    universe_filter TEXT,
    pre_filters TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    created_by TEXT,
    tags TEXT,
    remark TEXT,
    backtest_last_id INTEGER,
    backtest_last_sharpe REAL,
    backtest_last_total_return REAL,
    backtest_last_date TEXT
);

-- 策略版本表（时间线快照；每次用户点击「保存为版本 N+1」时写入；不可修改）
CREATE TABLE IF NOT EXISTS quant_strategy_version (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id INTEGER NOT NULL,
    version INTEGER NOT NULL,
    buy_rules TEXT NOT NULL,
    sell_rules TEXT NOT NULL,
    position_sizing TEXT,
    universe TEXT NOT NULL DEFAULT 'ALL',
    universe_filter TEXT,
    pre_filters TEXT,
    change_note TEXT,
    backtest_id INTEGER,
    created_at TEXT NOT NULL,
    created_by TEXT,
    is_rollback_from INTEGER
);

-- ================================================================
-- 索引
-- ================================================================

CREATE INDEX IF NOT EXISTS idx_quant_strategy_status
    ON quant_strategy(status);

CREATE INDEX IF NOT EXISTS idx_quant_strategy_category
    ON quant_strategy(category);

CREATE INDEX IF NOT EXISTS idx_quant_strategy_updated
    ON quant_strategy(updated_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_quant_strategy_version_unique
    ON quant_strategy_version(strategy_id, version);

CREATE INDEX IF NOT EXISTS idx_quant_strategy_version_strategy
    ON quant_strategy_version(strategy_id);

CREATE INDEX IF NOT EXISTS idx_quant_strategy_version_created
    ON quant_strategy_version(created_at DESC);
