-- ============================================================
-- Stock Watcher 数据库 Schema (MySQL)
-- 注意：字段类型/长度变更时，请同步修改 schema-sqlite.sql
-- ============================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(128) NOT NULL,
    totp_secret VARCHAR(64),
    enabled     TINYINT      DEFAULT 1,
    email       VARCHAR(128),
    phone       VARCHAR(32),
    role        VARCHAR(16)  DEFAULT 'USER',
    created_at  VARCHAR(32),
    updated_at  VARCHAR(32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 自选股表
CREATE TABLE IF NOT EXISTS sys_watchlist (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    stock_code  VARCHAR(16)  NOT NULL,
    created_at  VARCHAR(32),
    UNIQUE(user_id, stock_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 日线行情表
CREATE TABLE IF NOT EXISTS daily_quote (
    ts_code    VARCHAR(16)    NOT NULL,
    trade_date VARCHAR(8)     NOT NULL,
    open       DECIMAL(20,4),
    high       DECIMAL(20,4),
    low        DECIMAL(20,4),
    close      DECIMAL(20,4),
    pre_close  DECIMAL(20,4),
    change_amt DECIMAL(20,4),
    pct_chg    DECIMAL(20,4),
    vol        DECIMAL(20,4),
    amount     DECIMAL(20,4),
    PRIMARY KEY (ts_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 股票基本信息表
CREATE TABLE IF NOT EXISTS stock_basic (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    ts_code      VARCHAR(16)  NOT NULL UNIQUE,
    symbol       VARCHAR(16),
    name         VARCHAR(64),
    area         VARCHAR(32),
    industry     VARCHAR(64),
    fullname     VARCHAR(128),
    enname       VARCHAR(128),
    cnspell      VARCHAR(32),
    market       VARCHAR(16),
    exchange     VARCHAR(16),
    curr_type    VARCHAR(8),
    list_status  VARCHAR(4),
    list_date    VARCHAR(8),
    delist_date  VARCHAR(8),
    is_hs        VARCHAR(4),
    act_name     VARCHAR(128),
    act_ent_type VARCHAR(32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. 交易日历表
CREATE TABLE IF NOT EXISTS trade_cal (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange       VARCHAR(16),
    cal_date       VARCHAR(8)  NOT NULL,
    is_open        VARCHAR(4),
    pretrade_date  VARCHAR(8),
    UNIQUE(exchange, cal_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. 复权因子表
CREATE TABLE IF NOT EXISTS adj_factor (
    ts_code     VARCHAR(16)    NOT NULL,
    trade_date  VARCHAR(8)     NOT NULL,
    adj_factor  DECIMAL(20,4),
    PRIMARY KEY (ts_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. 分红送股表
CREATE TABLE IF NOT EXISTS dividend (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    ts_code       VARCHAR(16)    NOT NULL,
    end_date      VARCHAR(8),
    ann_date      VARCHAR(8),
    div_proc      VARCHAR(16),
    stk_div       DECIMAL(20,4),
    stk_bo_rate   DECIMAL(20,4),
    stk_co_rate   DECIMAL(20,4),
    cash_div      DECIMAL(20,4),
    cash_div_tax  DECIMAL(20,4),
    record_date   VARCHAR(8),
    ex_date       VARCHAR(8),
    pay_date      VARCHAR(8),
    div_listdate  VARCHAR(8),
    imp_ann_date  VARCHAR(8),
    base_date     VARCHAR(8),
    base_share    DECIMAL(20,4),
    UNIQUE(ts_code, end_date, ann_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 初始管理员账号（仅当表为空时插入，默认密码: admin123）
INSERT INTO sys_user (username, password, enabled, role)
SELECT 'admin', '$2a$10$pfuIlLGBbNZqO5xXa9oRKeEFABc4FIxs2SVY46UUG1xpA7o9tGn9u', 1, 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM sys_user);
