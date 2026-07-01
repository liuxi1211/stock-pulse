-- =====================================================================
-- 005-选股模块 Schema 定义 (SQLite)
-- 设计依据：
--   - 005-选股模块PRD.md §8.1 指标预计算表
--   - 02-db-design.md 表 1 的完整设计
--   - prototype/screening.html 使用的因子列（MA5 / MA20 / MACD / KDJ / RSI 等 38 个指标列）
--
-- 数据库约定：字段名 snake_case；数值 REAL；整数 INTEGER；日期 TEXT(YYYYMMDD)；
--            股票代码 TEXT(000001.SZ 格式)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 指标预计算表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_indicator_daily (
    ts_code     TEXT    NOT NULL,
    trade_date  TEXT    NOT NULL,
    close       REAL,
    high        REAL,
    low         REAL,
    open        REAL,
    volume      REAL,
    ma_5        REAL,
    ma_10       REAL,
    ma_20       REAL,
    ma_60       REAL,
    ma_120      REAL,
    ma_250      REAL,
    ema_5       REAL,
    ema_10      REAL,
    ema_20      REAL,
    ema_60      REAL,
    boll_upper  REAL,
    boll_mid    REAL,
    boll_lower  REAL,
    sar         REAL,
    rsi_6       REAL,
    rsi_14      REAL,
    rsi_28      REAL,
    macd_dif    REAL,
    macd_dea    REAL,
    macd_hist   REAL,
    kdj_k       REAL,
    kdj_d       REAL,
    kdj_j       REAL,
    adx_14      REAL,
    plus_di_14   REAL,
    minus_di_14    REAL,
    willr_14     REAL,
    cci_14       REAL,
    atr_14       REAL,
    vol_ma_5     REAL,
    vol_ma_20    REAL,
    vol_ma_60    REAL,
    obv         REAL,
    PRIMARY KEY (ts_code, trade_date)
);

-- 2. 关键查询优化：按 trade_date 过滤
CREATE INDEX IF NOT EXISTS idx_stock_indicator_daily_trade_date
    ON stock_indicator_daily(trade_date);

-- 3. 按 ts_code 查询优化（区间查询时按 ts_code JOIN）
CREATE INDEX IF NOT EXISTS idx_stock_indicator_daily_ts_code
    ON stock_indicator_daily(ts_code);

-- ---------------------------------------------------------------------
-- 2. 与现有表的查询优化索引（如果缺失时创建，不改变现有表结构，只新建索引）
-- ---------------------------------------------------------------------
-- stock_basic 表中已有 (ts_code) 索引（唯一索引，主键已提供，此处不新建；如果需要，
-- 建表前不重复建，避免重复创建）
