-- ============================================================
-- Stock Watcher 数据库 Schema (SQLite)
-- 注意：字段类型/长度变更时，请同步修改 schema-mysql.sql
-- ============================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    username    TEXT    NOT NULL UNIQUE,
    password    TEXT    NOT NULL,
    totp_secret TEXT,
    enabled     INTEGER DEFAULT 1,
    email       TEXT,
    phone       TEXT,
    role        TEXT    DEFAULT 'USER',
    created_at  TEXT,
    updated_at  TEXT
);

-- 2. 自选股表
CREATE TABLE IF NOT EXISTS sys_watchlist (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL,
    stock_code  TEXT    NOT NULL,
    created_at  TEXT,
    UNIQUE(user_id, stock_code)
);

-- 3. 日线行情表
CREATE TABLE IF NOT EXISTS daily_quote (
    ts_code    TEXT    NOT NULL,
    trade_date TEXT    NOT NULL,
    open       REAL,
    high       REAL,
    low        REAL,
    close      REAL,
    pre_close  REAL,
    change_amt REAL,
    pct_chg    REAL,
    vol        REAL,
    amount     REAL,
    PRIMARY KEY (ts_code, trade_date)
);

-- 4. 股票基本信息表
CREATE TABLE IF NOT EXISTS stock_basic (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code      TEXT    NOT NULL UNIQUE,
    symbol       TEXT,
    name         TEXT,
    area         TEXT,
    industry     TEXT,
    fullname     TEXT,
    enname       TEXT,
    cnspell      TEXT,
    market       TEXT,
    exchange     TEXT,
    curr_type    TEXT,
    list_status  TEXT,
    list_date    TEXT,
    delist_date  TEXT,
    is_hs        TEXT,
    act_name     TEXT,
    act_ent_type TEXT
);

-- 5. 交易日历表
CREATE TABLE IF NOT EXISTS trade_cal (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    exchange        TEXT,
    cal_date        TEXT    NOT NULL,
    is_open         TEXT,
    pretrade_date   TEXT,
    is_first_of_week     INTEGER DEFAULT 0,
    is_last_of_week      INTEGER DEFAULT 0,
    is_first_of_month    INTEGER DEFAULT 0,
    is_last_of_month     INTEGER DEFAULT 0,
    is_first_of_quarter  INTEGER DEFAULT 0,
    is_last_of_quarter   INTEGER DEFAULT 0,
    UNIQUE(exchange, cal_date)
);

-- 6. 复权因子表
CREATE TABLE IF NOT EXISTS adj_factor (
    ts_code     TEXT    NOT NULL,
    trade_date  TEXT    NOT NULL,
    adj_factor  REAL,
    PRIMARY KEY (ts_code, trade_date)
);

-- 7. 分红送股表
CREATE TABLE IF NOT EXISTS dividend (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code       TEXT    NOT NULL,
    end_date      TEXT,
    ann_date      TEXT,
    div_proc      TEXT,
    stk_div       REAL,
    stk_bo_rate   REAL,
    stk_co_rate   REAL,
    cash_div      REAL,
    cash_div_tax  REAL,
    record_date   TEXT,
    ex_date       TEXT,
    pay_date      TEXT,
    div_listdate  TEXT,
    imp_ann_date  TEXT,
    base_date     TEXT,
    base_share    REAL,
    UNIQUE(ts_code, end_date, ann_date)
);

-- 8. 选股方案表
CREATE TABLE IF NOT EXISTS screen_plan (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    screen_config TEXT         NOT NULL,
    created_at    VARCHAR(32),
    updated_at    VARCHAR(32)
);

-- 9. 选股结果表
CREATE TABLE IF NOT EXISTS screen_result (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id      INTEGER      NOT NULL,
    screen_date  VARCHAR(8)   NOT NULL,
    total_count  INTEGER,
    stocks_json  TEXT,
    params_json  TEXT,
    created_at   VARCHAR(32)
);

-- 10. 选股锁定表
CREATE TABLE IF NOT EXISTS screen_lock (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    result_id         INTEGER,
    plan_id           INTEGER,
    lock_date         VARCHAR(8),
    stocks_json       TEXT,
    ret_5d            REAL,
    ret_10d           REAL,
    ret_20d           REAL,
    benchmark_ret_5d  REAL,
    benchmark_ret_10d REAL,
    benchmark_ret_20d REAL,
    status            VARCHAR(16),
    created_at        VARCHAR(32),
    updated_at        VARCHAR(32)
);

-- 11. 每日基本面表（Tushare daily_basic：估值/换手率/市值）
CREATE TABLE IF NOT EXISTS daily_basic (
    trade_date      TEXT NOT NULL,
    ts_code         TEXT NOT NULL,
    close           REAL,
    turnover_rate   REAL,
    turnover_rate_f REAL,
    volume_ratio    REAL,
    pe              REAL,
    pe_ttm          REAL,
    pb              REAL,
    ps              REAL,
    ps_ttm          REAL,
    dv_ratio        REAL,
    dv_ttm          REAL,
    total_share     REAL,
    float_share     REAL,
    free_share      REAL,
    total_mv        REAL,
    circ_mv         REAL,
    PRIMARY KEY (trade_date, ts_code)
);
CREATE INDEX IF NOT EXISTS idx_daily_basic_tscode ON daily_basic (ts_code, trade_date);

-- 12. 财务指标表（Tushare fina_indicator：ROE/ROA/毛利率/同比/资产负债率等）
CREATE TABLE IF NOT EXISTS fina_indicator (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code            TEXT NOT NULL,
    end_date           TEXT NOT NULL,
    ann_date           TEXT,
    roe                REAL,
    roa                REAL,
    grossprofit_margin REAL,
    netprofit_margin   REAL,
    dt_netprofit_yoy   REAL,
    revenue_yoy        REAL,
    debt_to_assets     REAL,
    eps_yoy            REAL,
    UNIQUE (ts_code, end_date)
);
CREATE INDEX IF NOT EXISTS idx_fina_indicator_tscode ON fina_indicator (ts_code, end_date);

-- 13. 因子预计算快照表（每日收盘后预计算常用参数的技术面因子当日值）
CREATE TABLE IF NOT EXISTS factor_snapshot (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    trade_date   TEXT    NOT NULL,
    ts_code      TEXT    NOT NULL,
    factor_key   TEXT    NOT NULL,
    params_json  TEXT    NOT NULL DEFAULT '{}',
    output_index INTEGER NOT NULL DEFAULT 0,
    factor_value REAL,
    updated_at   TEXT,
    UNIQUE (trade_date, ts_code, factor_key, params_json, output_index)
);
CREATE INDEX IF NOT EXISTS idx_factor_snapshot_lookup ON factor_snapshot (trade_date, ts_code, factor_key);
CREATE INDEX IF NOT EXISTS idx_factor_snapshot_date ON factor_snapshot (trade_date);

-- 14. 策略主表（quant_strategy）
CREATE TABLE IF NOT EXISTS quant_strategy (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid            TEXT         NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    category        VARCHAR(32),
    scope           VARCHAR(16),
    status          VARCHAR(16)  DEFAULT 'DRAFT',
    tags            VARCHAR(512),
    current_version INTEGER      DEFAULT 1,
    created_at      VARCHAR(32),
    updated_at      VARCHAR(32)
);

-- 15. 策略版本快照表（quant_strategy_version，strategy_id 为外键 → quant_strategy.id）
CREATE TABLE IF NOT EXISTS quant_strategy_version (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id  INTEGER NOT NULL,
    version_no   INTEGER NOT NULL,
    config_json  TEXT    NOT NULL,
    changelog    VARCHAR(512),
    created_at   VARCHAR(32),
    UNIQUE(strategy_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_strategy_version_lookup ON quant_strategy_version (strategy_id, version_no);

-- 16. 回测主表/任务表（quant_backtest）
CREATE TABLE IF NOT EXISTS quant_backtest (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id         TEXT        NOT NULL UNIQUE,
    strategy_id     INTEGER     NOT NULL,
    version_no      INTEGER     NOT NULL,
    mode            VARCHAR(16) DEFAULT 'SINGLE',
    status          VARCHAR(16) DEFAULT 'PENDING',
    progress        INTEGER     DEFAULT 0,
    error_message   TEXT,
    override_config TEXT,
    benchmark       TEXT        DEFAULT '000300.SH',
    created_by      TEXT,
    started_at      TEXT,
    finished_at     TEXT,
    created_at      TEXT
);
CREATE INDEX IF NOT EXISTS idx_backtest_strategy_version ON quant_backtest (strategy_id, version_no);
CREATE INDEX IF NOT EXISTS idx_backtest_status ON quant_backtest (status);
CREATE INDEX IF NOT EXISTS idx_backtest_mode ON quant_backtest (mode);

-- 17. 回测报告表（quant_backtest_report，SINGLE 模式全量 JSON）
CREATE TABLE IF NOT EXISTS quant_backtest_report (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    backtest_id         INTEGER NOT NULL,
    metrics_json        TEXT,
    equity_curve_json   TEXT,
    benchmark_curve_json TEXT,
    daily_returns_json  TEXT,
    trades_json        TEXT,
    orders_json        TEXT,
    positions_json     TEXT,
    rebalance_diagnosis_json TEXT,
    effective_config_json TEXT,
    execution_diagnosis_json TEXT,
    created_at         TEXT,
    UNIQUE(backtest_id)
);

-- 18. 指数成分股权重表（tushare index_weight）
CREATE TABLE IF NOT EXISTS index_weight (
    ts_code    TEXT NOT NULL,
    trade_date TEXT NOT NULL,
    con_code   TEXT NOT NULL,
    weight     REAL,
    PRIMARY KEY (ts_code, trade_date, con_code)
);
CREATE INDEX IF NOT EXISTS idx_index_weight_date ON index_weight (ts_code, trade_date);

-- 19. 申万行业分类表（tushare index_classify，SWS2021 版本）
CREATE TABLE IF NOT EXISTS sw_industry (
    index_code  TEXT NOT NULL,
    index_name  TEXT,
    level       INTEGER,
    parent_code TEXT,
    src         TEXT NOT NULL DEFAULT 'SWS2021',
    PRIMARY KEY (index_code, src)
);

-- 20. 申万行业成分股表（tushare index_member_all）
CREATE TABLE IF NOT EXISTS sw_industry_member (
    ts_code     TEXT NOT NULL,
    index_code  TEXT NOT NULL,
    index_name  TEXT,
    in_date     TEXT,
    out_date    TEXT,
    is_new      TEXT,
    src         TEXT NOT NULL DEFAULT 'SWS2021',
    update_date TEXT NOT NULL,
    PRIMARY KEY (ts_code, index_code, update_date)
);
CREATE INDEX IF NOT EXISTS idx_sw_member_tscode ON sw_industry_member (ts_code);
CREATE INDEX IF NOT EXISTS idx_sw_member_index ON sw_industry_member (index_code, is_new);

-- 21. ST 戴帽摘帽表（tushare namechange）
CREATE TABLE IF NOT EXISTS stock_namechange (
    ts_code        TEXT NOT NULL,
    name           TEXT,
    start_date     TEXT,
    end_date       TEXT,
    change_reason  TEXT,
    PRIMARY KEY (ts_code, start_date)
);
CREATE INDEX IF NOT EXISTS idx_namechange_tscode ON stock_namechange (ts_code);

-- 22. 停复牌表（tushare suspend_d）
CREATE TABLE IF NOT EXISTS stock_suspend_d (
    ts_code      TEXT NOT NULL,
    trade_date   TEXT NOT NULL,
    susp_reason  TEXT,
    resump_date  TEXT,
    PRIMARY KEY (ts_code, trade_date)
);
CREATE INDEX IF NOT EXISTS idx_suspend_tscode_date ON stock_suspend_d (ts_code, trade_date);

-- 23. 涨跌停价表（tushare stk_limit）
CREATE TABLE IF NOT EXISTS stock_stk_limit (
    ts_code     TEXT NOT NULL,
    trade_date  TEXT NOT NULL,
    pre_close   REAL,
    up_limit    REAL,
    down_limit  REAL,
    PRIMARY KEY (ts_code, trade_date)
);
CREATE INDEX IF NOT EXISTS idx_limit_tscode_date ON stock_stk_limit (ts_code, trade_date);

-- 24. 利润表（tushare income，doc_id=33）
CREATE TABLE IF NOT EXISTS income (
    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code                  TEXT NOT NULL,
    ann_date                 TEXT,
    f_ann_date               TEXT,
    end_date                 TEXT NOT NULL,
    report_type              TEXT,
    comp_type                TEXT,
    basic_eps                REAL,
    diluted_eps              REAL,
    total_revenue            REAL,
    revenue                  REAL,
    total_cogs               REAL,
    operate_cost             REAL,
    operate_profit           REAL,
    non_oper_income          REAL,
    non_oper_exp             REAL,
    total_profit             REAL,
    n_income                 REAL,
    n_income_attr_p          REAL,
    minority_interest        REAL,
    adjust_profit            REAL,
    income_tax               REAL,
    n_income_yoy             REAL,
    dt_profit_yoy            REAL,
    sell_exp                 REAL,
    admin_exp                REAL,
    financial_exp            REAL,
    rd_exp                   REAL,
    impair_end_invest        REAL,
    impair_end_oper          REAL,
    invest_income            REAL,
    invest_income_inc        REAL,
    invest_income_dec        REAL,
    fairvalue_change_income  REAL,
    exchange_gain            REAL,
    asset_dispose_income     REAL,
    other_income             REAL,
    operate_n_income         REAL,
    credit_impair_loss       REAL,
    asset_impair_loss        REAL,
    bbit                     REAL,
    bbit_yoy                 REAL,
    operate_profit_income_yoy REAL,
    update_flag              TEXT,
    UNIQUE (ts_code, end_date, report_type)
);
CREATE INDEX IF NOT EXISTS idx_income_tscode ON income (ts_code, end_date);

-- 25. 资产负债表（tushare balancesheet，doc_id=36）
CREATE TABLE IF NOT EXISTS balancesheet (
    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code                     TEXT NOT NULL,
    ann_date                    TEXT,
    f_ann_date                  TEXT,
    end_date                    TEXT NOT NULL,
    report_type                 TEXT,
    comp_type                   TEXT,
    monetary_funds              REAL,
    accounts_rece               REAL,
    notes_rece                  REAL,
    accounts_rece_fin          REAL,
    other_rece                  REAL,
    prepayment                  REAL,
    dividends_rece              REAL,
    int_rece                    REAL,
    inventories                 REAL,
    non_current_assets_in_1_yr  REAL,
    other_current_assets        REAL,
    total_current_assets        REAL,
    equity_joint_cap            REAL,
    lt_receivable               REAL,
    eqt_invest                  REAL,
    inv_real_estate             REAL,
    fix_assets_nca              REAL,
    cip                         REAL,
    construction_materials      REAL,
    intang_assets               REAL,
    goodwill                    REAL,
    lt_amort_deferred_exp      REAL,
    defer_tax_assets            REAL,
    other_non_current_assets    REAL,
    total_non_current_assets    REAL,
    total_assets                REAL,
    lt_borr                     REAL,
    notes_payable               REAL,
    accounts_payable            REAL,
    accounts_payable_fin       REAL,
    prepayment_receivables      REAL,
    wage_payable                REAL,
    taxes_surcharges            REAL,
    other_payable               REAL,
    non_current_liab_in_1_yr    REAL,
    other_current_liab          REAL,
    total_current_liab         REAL,
    long_term_borr              REAL,
    ppayable_bonds              REAL,
    long_term_payable           REAL,
    specific_payable            REAL,
    estimated_liab              REAL,
    defer_tax_liab              REAL,
    defer_inc_non_curr_liab     REAL,
    other_non_current_liab      REAL,
    total_non_current_liab      REAL,
    total_liab                  REAL,
    share_capital               REAL,
    capital_reserve             REAL,
    treasury_stock             REAL,
    specific_reserves           REAL,
    surplus_reserve             REAL,
    general_risk_reserve        REAL,
    undistributed_profit        REAL,
    equity_parent_company       REAL,
    minority_interest           REAL,
    total_equity                REAL,
    total_liab_equity           REAL,
    accounts_rece_decr          REAL,
    accounts_rece_fin_decr     REAL,
    minority_interest_inc       REAL,
    minority_interest_dec       REAL,
    update_flag                 TEXT,
    UNIQUE (ts_code, end_date, report_type)
);
CREATE INDEX IF NOT EXISTS idx_balancesheet_tscode ON balancesheet (ts_code, end_date);

-- 26. 现金流量表（tushare cashflow，doc_id=44）
CREATE TABLE IF NOT EXISTS cashflow (
    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code                     TEXT NOT NULL,
    ann_date                    TEXT,
    f_ann_date                  TEXT,
    end_date                    TEXT NOT NULL,
    report_type                 TEXT,
    comp_type                   TEXT,
    n_cashflow_act              REAL,
    n_cashflow_inv_act          REAL,
    n_cash_flows_fnc_act        REAL,
    free_cashflow               REAL,
    c_fr_sale_sg                REAL,
    c_fr_oth_sg                 REAL,
    c_paid_goods_s              REAL,
    c_paid_to_for_empl          REAL,
    c_paid_for_taxes            REAL,
    c_paid_oth_op_f             REAL,
    c_paid_invest               REAL,
    c_paid_invest_f             REAL,
    c_pay_acq_const_fiolta     REAL,
    c_pay_acq_int_long_loan     REAL,
    disp_fix_assets_oth         REAL,
    n_invest_loss               REAL,
    c_fr_fnc_loan               REAL,
    c_fr_fnc_oth                REAL,
    proceeds_long_loan          REAL,
    c_paid_fin_fees             REAL,
    c_pay_dist_dpcp_int_exp    REAL,
    end_bal_cash                REAL,
    beg_bal_cash                REAL,
    n_cash_equ                  REAL,
    n_increase_incl_child       REAL,
    prov_depr_assets            REAL,
    depr_fa_coga_dpba           REAL,
    amort_intang                REAL,
    amort_lt_deferred_exp       REAL,
    loss_disp_fa                REAL,
    loss_scr_fa                 REAL,
    loss_fair_valu              REAL,
    fin_exp                     REAL,
    loss_inv                    REAL,
    dec_def_inc_tax_assets      REAL,
    inc_def_inc_tax_liab        REAL,
    dec_inv                     REAL,
    dec_oper_rece               REAL,
    inc_oper_payable            REAL,
    net_profit                  REAL,
    minority_interest           REAL,
    undistributed_profit_in     REAL,
    update_flag                 TEXT,
    UNIQUE (ts_code, end_date, report_type)
);
CREATE INDEX IF NOT EXISTS idx_cashflow_tscode ON cashflow (ts_code, end_date);

-- 27. 业绩预告（tushare forecast，doc_id=45，保留多次预告历史）
CREATE TABLE IF NOT EXISTS forecast (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code           TEXT    NOT NULL,
    ann_date          TEXT,
    end_date          TEXT    NOT NULL,
    type              TEXT,
    p_change_min      REAL,
    p_change_max      REAL,
    net_profit_min    REAL,
    net_profit_max    REAL,
    last_parent_net  REAL,
    summary           TEXT,
    change_reason     TEXT,
    UNIQUE (ts_code, end_date, ann_date)
);
CREATE INDEX IF NOT EXISTS idx_forecast_tscode ON forecast (ts_code, end_date);

-- 28. 业绩快报（tushare express，doc_id=46，一个报告期一条快报）
CREATE TABLE IF NOT EXISTS express (
    id                              INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code                         TEXT    NOT NULL,
    ann_date                        TEXT,
    end_date                        TEXT    NOT NULL,
    revenue                         REAL,
    operate_profit                  REAL,
    total_profit                    REAL,
    n_income                        REAL,
    total_assets                    REAL,
    total_hldr_eqy_exc_min_int      REAL,
    basic_eps                       REAL,
    diluted_eps                     REAL,
    growth_yield                    REAL,
    or_growth_yield                 REAL,
    yst_net_profit                  REAL,
    bm_net_profit                   REAL,
    bm_growth_sales                 REAL,
    update_flag                     TEXT,
    UNIQUE (ts_code, end_date)
);
CREATE INDEX IF NOT EXISTS idx_express_tscode ON express (ts_code, end_date);

-- 初始管理员账号（仅当表为空时插入，默认密码: admin123）
INSERT INTO sys_user (username, password, enabled, role)
SELECT 'admin', '$2a$10$pfuIlLGBbNZqO5xXa9oRKeEFABc4FIxs2SVY46UUG1xpA7o9tGn9u', 1, 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM sys_user);
