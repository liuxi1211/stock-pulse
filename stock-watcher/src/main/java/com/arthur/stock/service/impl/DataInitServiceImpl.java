package com.arthur.stock.service.impl;

import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.DataInitProgress;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 数据初始化服务实现，按步骤清除旧数据并全量拉取指定接口的数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataInitServiceImpl implements DataInitService {

    @Value("${app.db-type:mysql}")
    private String dbType;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** I/O 密集型任务使用虚拟线程，避免占用 ForkJoinPool.commonPool */
    private static final Executor IO_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 固定执行顺序，确保依赖关系正确（stock_basic 先于 per-stock 步骤） */
    private static final List<InitStep> EXECUTION_ORDER = List.of(
            InitStep.STOCK_BASIC, InitStep.TRADE_CAL, InitStep.INDEX_WEIGHT, InitStep.SW_INDUSTRY,
            InitStep.DAILY, InitStep.ADJ_FACTOR, InitStep.DIVIDEND,
            InitStep.NAMECHANGE, InitStep.SUSPEND_D, InitStep.STK_LIMIT,
            InitStep.INCOME, InitStep.BALANCESHEET, InitStep.CASHFLOW,
            InitStep.FORECAST, InitStep.EXPRESS);

    private final JdbcTemplate jdbcTemplate;
    private final StockBasicService stockBasicService;
    private final TradeCalService tradeCalService;
    private final IndexWeightService indexWeightService;
    private final SwIndustryService swIndustryService;
    private final DailyQuoteService dailyQuoteService;
    private final AdjFactorService adjFactorService;
    private final DividendService dividendService;
    private final StockNamechangeService stockNamechangeService;
    private final StockSuspendDService stockSuspendDService;
    private final StockStkLimitService stockStkLimitService;
    private final IncomeService incomeService;
    private final BalancesheetService balancesheetService;
    private final CashflowService cashflowService;
    private final ForecastService forecastService;
    private final ExpressService expressService;
    private final CacheManager cacheManager;

    private final AtomicReference<DataInitProgress> progressRef = new AtomicReference<>(
            DataInitProgress.builder().status("IDLE").build());

    @Override
    public DataInitProgress initialize(List<String> steps) {
        DataInitProgress current = progressRef.get();
        if ("RUNNING".equals(current.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "数据初始化正在进行中，请勿重复执行");
        }

        List<InitStep> parsedSteps = parseSteps(steps);

        DataInitProgress starting = DataInitProgress.builder()
                .status("RUNNING")
                .currentStep("准备中")
                .startTime(LocalDateTime.now().format(DATETIME_FMT))
                .build();
        progressRef.set(starting);

        CompletableFuture.runAsync(() -> {
            try {
                doInitialize(parsedSteps);
            } catch (Exception e) {
                log.error("Data initialization failed", e);
                progressRef.updateAndGet(p -> p.toBuilder()
                        .status("FAILED")
                        .currentStep("初始化失败")
                        .message(e.getMessage())
                        .endTime(LocalDateTime.now().format(DATETIME_FMT))
                        .build());
            }
        }, IO_EXECUTOR);

        return progressRef.get();
    }

    @Override
    public DataInitProgress getStatus() {
        return progressRef.get();
    }

    private void doInitialize(List<InitStep> steps) {
        String stepNames = steps.stream().map(InitStep::getLabel).collect(Collectors.joining(" → "));
        log.info("===== Data initialization started: {} =====", stepNames);

        // 清除选中步骤对应的表数据
        clearSelectedData(steps);

        // 获取股票列表（stock_basic 步骤从 Tushare 拉取，否则从本地读取）
        List<StockBasicDTO> stocks = resolveStockList(steps);
        log.info("Stock list resolved: {} stocks", stocks.size());

        // 按固定顺序执行选中的步骤
        for (InitStep step : EXECUTION_ORDER) {
            if (!steps.contains(step)) {
                continue;
            }
            switch (step) {
                case STOCK_BASIC -> executeStockBasic(stocks);
                case TRADE_CAL -> executeTradeCal();
                case INDEX_WEIGHT -> executeIndexWeight();
                case SW_INDUSTRY -> executeSwIndustry();
                case DAILY -> executeDaily(stocks);
                case ADJ_FACTOR -> executeAdjFactor(stocks);
                case DIVIDEND -> executeDividend(stocks);
                case NAMECHANGE -> stockNamechangeService.fetchAndSaveAll();
                case SUSPEND_D -> stockSuspendDService.fetchAndSaveAll();
                case STK_LIMIT -> stockStkLimitService.fetchAndSaveAll();
                case INCOME -> executeIncome(stocks);
                case BALANCESHEET -> executeBalancesheet(stocks);
                case CASHFLOW -> executeCashflow(stocks);
                case FORECAST -> executeForecast(stocks);
                case EXPRESS -> executeExpress(stocks);
            }
        }

        clearKlineCache();

        progressRef.updateAndGet(p -> p.toBuilder()
                .status("SUCCESS")
                .currentStep("初始化完成")
                .endTime(LocalDateTime.now().format(DATETIME_FMT))
                .build());

        log.info("===== Data initialization finished =====");
    }

    // ==================== 步骤执行方法 ====================

    private void executeStockBasic(List<StockBasicDTO> stocks) {
        // stock_basic 已在 resolveStockList 中拉取并保存，此处只记录日志
        updateStep("拉取股票基础信息");
        log.info("Stock basic synced: {} stocks", stocks.size());
    }

    private void executeTradeCal() {
        updateStep("拉取交易日历");
        // 老库幂等迁移：trade_cal 表新增 6 个调仓标记列（CREATE TABLE IF NOT EXISTS 不会为已存在的表加列）。
        ensureTradeCalRebalanceColumns();
        String startDate = LocalDate.now().minusYears(30).format(DATE_FMT);
        String endDate = LocalDate.now().format(DATE_FMT);
        tradeCalService.fetchAndSaveTradeCal(null, startDate, endDate);
        log.info("Trade calendar synced");
    }

    /** trade_cal 需要幂等补齐的 6 个调仓标记列。 */
    private static final List<String> TRADE_CAL_REBALANCE_COLUMNS = List.of(
            "is_first_of_week", "is_last_of_week",
            "is_first_of_month", "is_last_of_month",
            "is_first_of_quarter", "is_last_of_quarter");

    /**
     * 幂等地为已存在的 trade_cal 表补齐 6 个调仓标记列。
     * <p>
     * 全新建库时 schema-*.sql / CREATE_TABLE_SQL_* 已包含这些列；但对已运行的老库，
     * {@code CREATE TABLE IF NOT EXISTS} 不会加列，故在此按方言检测列是否存在后 ADD COLUMN。
     * <ul>
     *   <li>MySQL：查 INFORMATION_SCHEMA.COLUMNS；</li>
     *   <li>SQLite：查 PRAGMA table_info(trade_cal)。</li>
     * </ul>
     */
    private void ensureTradeCalRebalanceColumns() {
        for (String column : TRADE_CAL_REBALANCE_COLUMNS) {
            if (isSqlite()) {
                if (!sqliteColumnExists("trade_cal", column)) {
                    jdbcTemplate.execute("ALTER TABLE trade_cal ADD COLUMN " + column + " INTEGER DEFAULT 0");
                    log.info("trade_cal: added column {} (sqlite)", column);
                }
            } else {
                if (!mysqlColumnExists("trade_cal", column)) {
                    jdbcTemplate.execute("ALTER TABLE trade_cal ADD COLUMN " + column + " TINYINT DEFAULT 0");
                    log.info("trade_cal: added column {} (mysql)", column);
                }
            }
        }
    }

    private boolean isSqlite() {
        return "sqlite".equalsIgnoreCase(dbType);
    }

    private boolean sqliteColumnExists(String table, String column) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info('" + table + "') WHERE name = ?",
                Integer.class, column);
        return cnt != null && cnt > 0;
    }

    private boolean mysqlColumnExists(String table, String column) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return cnt != null && cnt > 0;
    }

    /** 指数成分股权重：拉取沪深300/中证500/上证50/中证1000 最近 5 年历史快照（回测防幸存者偏差） */
    private static final List<String> INDEX_CODES = List.of(
            "000300.SH", "000905.SH", "000016.SH", "000852.SH");

    private void executeIndexWeight() {
        updateStep("拉取指数成分权重");
        String startDate = LocalDate.now().minusYears(5).format(DATE_FMT);
        String endDate = LocalDate.now().format(DATE_FMT);
        for (String indexCode : INDEX_CODES) {
            try {
                int n = indexWeightService.fetchAndSaveRange(indexCode, startDate, endDate);
                log.info("Index weight synced: {} ({} records)", indexCode, n);
            } catch (Exception e) {
                log.error("Index weight sync failed for {}: {}", indexCode, e.getMessage(), e);
            }
        }
    }

    /** 申万行业分类（SWS2021）：先拉分类，再全量分页拉成分股。clearSelectedData 已 DROP sw_industry，
     *  这里额外重建 sw_industry_member（两表属于同一步骤的关联数据）。 */
    private void executeSwIndustry() {
        updateStep("拉取申万行业分类");
        // 成员表与主表强关联，主表 DROP 后成员表也一并重建
        Map<String, String> createTableSql = getCreateTableSql();
        jdbcTemplate.execute("DROP TABLE IF EXISTS sw_industry_member");
        jdbcTemplate.execute(createTableSql.get("sw_industry_member"));
        log.info("Recreated table: sw_industry_member");
        try {
            int classify = swIndustryService.fetchAndSaveClassify("SWS2021");
            log.info("SW industry classify synced: {} industries", classify);
        } catch (Exception e) {
            log.error("SW industry classify sync failed: {}", e.getMessage(), e);
        }
        try {
            int members = swIndustryService.fetchAndSaveAllMembers("SWS2021");
            log.info("SW industry members synced: {} records", members);
        } catch (Exception e) {
            log.error("SW industry members sync failed: {}", e.getMessage(), e);
        }
    }

    private void executeDaily(List<StockBasicDTO> stocks) {
        updateStep("拉取日线行情");
        progressRef.updateAndGet(p -> p.toBuilder().totalStocks(stocks.size()).processedStocks(0).build());
        for (int i = 0; i < stocks.size(); i++) {
            String tsCode = stocks.get(i).getTsCode();
            try {
                dailyQuoteService.fetchAndSaveDailyQuotes(tsCode);
            } catch (Exception e) {
                log.warn("Failed to fetch daily quotes for {}: {}", tsCode, e.getMessage());
            }
            reportProgress("拉取日线行情", i + 1, stocks.size());
        }
    }

    private void executeAdjFactor(List<StockBasicDTO> stocks) {
        updateStep("拉取复权因子");
        progressRef.updateAndGet(p -> p.toBuilder().totalStocks(stocks.size()).processedStocks(0).build());
        for (int i = 0; i < stocks.size(); i++) {
            String tsCode = stocks.get(i).getTsCode();
            try {
                adjFactorService.fetchAndSaveAdjFactor(tsCode);
            } catch (Exception e) {
                log.warn("Failed to fetch adj_factor for {}: {}", tsCode, e.getMessage());
            }
            reportProgress("拉取复权因子", i + 1, stocks.size());
        }
    }

    private void executeDividend(List<StockBasicDTO> stocks) {
        updateStep("拉取分红送股数据");
        progressRef.updateAndGet(p -> p.toBuilder().totalStocks(stocks.size()).processedStocks(0).build());
        for (int i = 0; i < stocks.size(); i++) {
            String tsCode = stocks.get(i).getTsCode();
            try {
                dividendService.fetchAndSaveDividend(tsCode);
            } catch (Exception e) {
                log.warn("Failed to fetch dividend for {}: {}", tsCode, e.getMessage());
            }
            reportProgress("拉取分红送股数据", i + 1, stocks.size());
        }
    }

    private void executeIncome(List<StockBasicDTO> stocks) {
        updateStep("拉取利润表数据");
        progressRef.updateAndGet(p -> p.toBuilder().totalStocks(stocks.size()).processedStocks(0).build());
        String startDate = LocalDate.now().minusYears(30).format(DATE_FMT);
        String endDate = LocalDate.now().format(DATE_FMT);
        for (int i = 0; i < stocks.size(); i++) {
            String tsCode = stocks.get(i).getTsCode();
            try {
                incomeService.fetchAndSaveIncome(tsCode, startDate, endDate);
            } catch (Exception e) {
                log.warn("Failed to fetch income for {}: {}", tsCode, e.getMessage(), e);
            }
            reportProgress("拉取利润表数据", i + 1, stocks.size());
        }
    }

    private void executeBalancesheet(List<StockBasicDTO> stocks) {
        updateStep("拉取资产负债表数据");
        progressRef.updateAndGet(p -> p.toBuilder().totalStocks(stocks.size()).processedStocks(0).build());
        String startDate = LocalDate.now().minusYears(30).format(DATE_FMT);
        String endDate = LocalDate.now().format(DATE_FMT);
        for (int i = 0; i < stocks.size(); i++) {
            String tsCode = stocks.get(i).getTsCode();
            try {
                balancesheetService.fetchAndSaveBalancesheet(tsCode, startDate, endDate);
            } catch (Exception e) {
                log.warn("Failed to fetch balancesheet for {}: {}", tsCode, e.getMessage(), e);
            }
            reportProgress("拉取资产负债表数据", i + 1, stocks.size());
        }
    }

    private void executeCashflow(List<StockBasicDTO> stocks) {
        updateStep("拉取现金流量表数据");
        progressRef.updateAndGet(p -> p.toBuilder().totalStocks(stocks.size()).processedStocks(0).build());
        String startDate = LocalDate.now().minusYears(30).format(DATE_FMT);
        String endDate = LocalDate.now().format(DATE_FMT);
        for (int i = 0; i < stocks.size(); i++) {
            String tsCode = stocks.get(i).getTsCode();
            try {
                cashflowService.fetchAndSaveCashflow(tsCode, startDate, endDate);
            } catch (Exception e) {
                log.warn("Failed to fetch cashflow for {}: {}", tsCode, e.getMessage(), e);
            }
            reportProgress("拉取现金流量表数据", i + 1, stocks.size());
        }
    }

    private void executeForecast(List<StockBasicDTO> stocks) {
        updateStep("拉取业绩预告数据");
        progressRef.updateAndGet(p -> p.toBuilder().totalStocks(stocks.size()).processedStocks(0).build());
        String startDate = LocalDate.now().minusYears(30).format(DATE_FMT);
        String endDate = LocalDate.now().format(DATE_FMT);
        for (int i = 0; i < stocks.size(); i++) {
            String tsCode = stocks.get(i).getTsCode();
            try {
                forecastService.fetchAndSaveForecast(tsCode, startDate, endDate);
            } catch (Exception e) {
                log.warn("Failed to fetch forecast for {}: {}", tsCode, e.getMessage(), e);
            }
            reportProgress("拉取业绩预告数据", i + 1, stocks.size());
        }
    }

    private void executeExpress(List<StockBasicDTO> stocks) {
        updateStep("拉取业绩快报数据");
        progressRef.updateAndGet(p -> p.toBuilder().totalStocks(stocks.size()).processedStocks(0).build());
        String startDate = LocalDate.now().minusYears(30).format(DATE_FMT);
        String endDate = LocalDate.now().format(DATE_FMT);
        for (int i = 0; i < stocks.size(); i++) {
            String tsCode = stocks.get(i).getTsCode();
            try {
                expressService.fetchAndSaveExpress(tsCode, startDate, endDate);
            } catch (Exception e) {
                log.warn("Failed to fetch express for {}: {}", tsCode, e.getMessage(), e);
            }
            reportProgress("拉取业绩快报数据", i + 1, stocks.size());
        }
    }

    // ==================== 辅助方法 ====================

    private List<StockBasicDTO> resolveStockList(List<InitStep> steps) {
        if (steps.contains(InitStep.STOCK_BASIC)) {
            return stockBasicService.fetchAndSaveStockBasic();
        }
        // 从本地数据库读取已有股票列表
        List<StockBasicDTO> local = stockBasicService.queryLocal(null, null, null, null);
        if (local.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "本地无股票基础信息，请先初始化 stock_basic 步骤");
        }
        return local;
    }

    private static final Map<String, String> CREATE_TABLE_SQL_MYSQL = Map.ofEntries(
            Map.entry("stock_basic", "CREATE TABLE IF NOT EXISTS stock_basic ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "ts_code VARCHAR(16) NOT NULL UNIQUE,"
                    + "symbol VARCHAR(16),name VARCHAR(64),area VARCHAR(32),industry VARCHAR(64),fullname VARCHAR(128),enname VARCHAR(128),"
                    + "cnspell VARCHAR(32),market VARCHAR(16),exchange VARCHAR(16),curr_type VARCHAR(8),list_status VARCHAR(4),"
                    + "list_date VARCHAR(8),delist_date VARCHAR(8),is_hs VARCHAR(4),act_name VARCHAR(128),act_ent_type VARCHAR(32)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("trade_cal", "CREATE TABLE IF NOT EXISTS trade_cal ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "exchange VARCHAR(16),cal_date VARCHAR(8) NOT NULL,is_open VARCHAR(4),pretrade_date VARCHAR(8),"
                    + "is_first_of_week TINYINT DEFAULT 0,is_last_of_week TINYINT DEFAULT 0,"
                    + "is_first_of_month TINYINT DEFAULT 0,is_last_of_month TINYINT DEFAULT 0,"
                    + "is_first_of_quarter TINYINT DEFAULT 0,is_last_of_quarter TINYINT DEFAULT 0,"
                    + "UNIQUE(exchange, cal_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("daily_quote", "CREATE TABLE IF NOT EXISTS daily_quote ("
                    + "ts_code VARCHAR(16) NOT NULL,trade_date VARCHAR(8) NOT NULL,"
                    + "open DECIMAL(20,4),high DECIMAL(20,4),low DECIMAL(20,4),close DECIMAL(20,4),pre_close DECIMAL(20,4),change_amt DECIMAL(20,4),"
                    + "pct_chg DECIMAL(20,4),vol DECIMAL(20,4),amount DECIMAL(20,4),"
                    + "PRIMARY KEY (ts_code, trade_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("adj_factor", "CREATE TABLE IF NOT EXISTS adj_factor ("
                    + "ts_code VARCHAR(16) NOT NULL,trade_date VARCHAR(8) NOT NULL,adj_factor DECIMAL(20,4),"
                    + "PRIMARY KEY (ts_code, trade_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("dividend", "CREATE TABLE IF NOT EXISTS dividend ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "ts_code VARCHAR(16) NOT NULL,end_date VARCHAR(8),ann_date VARCHAR(8),div_proc VARCHAR(16),"
                    + "stk_div DECIMAL(20,4),stk_bo_rate DECIMAL(20,4),stk_co_rate DECIMAL(20,4),cash_div DECIMAL(20,4),cash_div_tax DECIMAL(20,4),"
                    + "record_date VARCHAR(8),ex_date VARCHAR(8),pay_date VARCHAR(8),div_listdate VARCHAR(8),imp_ann_date VARCHAR(8),"
                    + "base_date VARCHAR(8),base_share DECIMAL(20,4),"
                    + "UNIQUE(ts_code, end_date, ann_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("index_weight", "CREATE TABLE IF NOT EXISTS index_weight ("
                    + "ts_code VARCHAR(16) NOT NULL,trade_date VARCHAR(8) NOT NULL,"
                    + "con_code VARCHAR(16) NOT NULL,weight DECIMAL(10,6),"
                    + "PRIMARY KEY (ts_code, trade_date, con_code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("sw_industry", "CREATE TABLE IF NOT EXISTS sw_industry ("
                    + "index_code VARCHAR(32) NOT NULL,index_name VARCHAR(64),"
                    + "level INT,parent_code VARCHAR(32),"
                    + "src VARCHAR(16) NOT NULL DEFAULT 'SWS2021',"
                    + "PRIMARY KEY (index_code, src)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("sw_industry_member", "CREATE TABLE IF NOT EXISTS sw_industry_member ("
                    + "ts_code VARCHAR(16) NOT NULL,index_code VARCHAR(32) NOT NULL,"
                    + "index_name VARCHAR(64),in_date VARCHAR(8),out_date VARCHAR(8),"
                    + "is_new VARCHAR(4),src VARCHAR(16) NOT NULL DEFAULT 'SWS2021',update_date VARCHAR(8) NOT NULL,"
                    + "PRIMARY KEY (ts_code, index_code, update_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("screen_plan", "CREATE TABLE IF NOT EXISTS screen_plan ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "name VARCHAR(128) NOT NULL,description VARCHAR(512),screen_config TEXT NOT NULL,"
                    + "created_at VARCHAR(32),updated_at VARCHAR(32)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("screen_result", "CREATE TABLE IF NOT EXISTS screen_result ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "plan_id BIGINT NOT NULL,screen_date VARCHAR(8) NOT NULL,total_count INT,"
                    + "stocks_json TEXT,params_json TEXT,created_at VARCHAR(32)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("screen_lock", "CREATE TABLE IF NOT EXISTS screen_lock ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "result_id BIGINT,plan_id BIGINT,lock_date VARCHAR(8),stocks_json TEXT,"
                    + "ret_5d DECIMAL(20,4),ret_10d DECIMAL(20,4),ret_20d DECIMAL(20,4),"
                    + "benchmark_ret_5d DECIMAL(20,4),benchmark_ret_10d DECIMAL(20,4),benchmark_ret_20d DECIMAL(20,4),"
                    + "status VARCHAR(16),created_at VARCHAR(32),updated_at VARCHAR(32)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("stock_namechange", "CREATE TABLE IF NOT EXISTS stock_namechange ("
                    + "ts_code VARCHAR(16) NOT NULL,name VARCHAR(64),"
                    + "start_date VARCHAR(8),end_date VARCHAR(8),change_reason VARCHAR(64),"
                    + "PRIMARY KEY (ts_code, start_date),"
                    + "INDEX idx_namechange_tscode (ts_code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("stock_suspend_d", "CREATE TABLE IF NOT EXISTS stock_suspend_d ("
                    + "ts_code VARCHAR(16) NOT NULL,trade_date VARCHAR(8) NOT NULL,"
                    + "susp_reason VARCHAR(128),resump_date VARCHAR(8),"
                    + "PRIMARY KEY (ts_code, trade_date),"
                    + "INDEX idx_suspend_tscode_date (ts_code, trade_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("stock_stk_limit", "CREATE TABLE IF NOT EXISTS stock_stk_limit ("
                    + "ts_code VARCHAR(16) NOT NULL,trade_date VARCHAR(8) NOT NULL,"
                    + "pre_close DOUBLE,up_limit DOUBLE,down_limit DOUBLE,"
                    + "PRIMARY KEY (ts_code, trade_date),"
                    + "INDEX idx_limit_tscode_date (ts_code, trade_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("income", "CREATE TABLE IF NOT EXISTS income ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "ts_code VARCHAR(16) NOT NULL,ann_date VARCHAR(8),f_ann_date VARCHAR(8),"
                    + "end_date VARCHAR(8) NOT NULL,report_type VARCHAR(4),comp_type VARCHAR(4),"
                    + "basic_eps DECIMAL(20,4),diluted_eps DECIMAL(20,4),total_revenue DECIMAL(20,4),"
                    + "revenue DECIMAL(20,4),total_cogs DECIMAL(20,4),operate_cost DECIMAL(20,4),"
                    + "operate_profit DECIMAL(20,4),non_oper_income DECIMAL(20,4),non_oper_exp DECIMAL(20,4),"
                    + "total_profit DECIMAL(20,4),n_income DECIMAL(20,4),n_income_attr_p DECIMAL(20,4),"
                    + "minority_interest DECIMAL(20,4),adjust_profit DECIMAL(20,4),income_tax DECIMAL(20,4),"
                    + "n_income_yoy DECIMAL(20,4),dt_profit_yoy DECIMAL(20,4),sell_exp DECIMAL(20,4),"
                    + "admin_exp DECIMAL(20,4),financial_exp DECIMAL(20,4),rd_exp DECIMAL(20,4),"
                    + "impair_end_invest DECIMAL(20,4),impair_end_oper DECIMAL(20,4),"
                    + "invest_income DECIMAL(20,4),invest_income_inc DECIMAL(20,4),invest_income_dec DECIMAL(20,4),"
                    + "fairvalue_change_income DECIMAL(20,4),exchange_gain DECIMAL(20,4),"
                    + "asset_dispose_income DECIMAL(20,4),other_income DECIMAL(20,4),operate_n_income DECIMAL(20,4),"
                    + "credit_impair_loss DECIMAL(20,4),asset_impair_loss DECIMAL(20,4),"
                    + "bbit DECIMAL(20,4),bbit_yoy DECIMAL(20,4),operate_profit_income_yoy DECIMAL(20,4),"
                    + "update_flag VARCHAR(4),"
                    + "UNIQUE KEY uk_income (ts_code, end_date, report_type),"
                    + "INDEX idx_income_tscode (ts_code, end_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("balancesheet", "CREATE TABLE IF NOT EXISTS balancesheet ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "ts_code VARCHAR(16) NOT NULL,ann_date VARCHAR(8),f_ann_date VARCHAR(8),"
                    + "end_date VARCHAR(8) NOT NULL,report_type VARCHAR(4),comp_type VARCHAR(4),"
                    + "monetary_funds DECIMAL(20,4),accounts_rece DECIMAL(20,4),notes_rece DECIMAL(20,4),"
                    + "accounts_rece_fin DECIMAL(20,4),other_rece DECIMAL(20,4),prepayment DECIMAL(20,4),"
                    + "dividends_rece DECIMAL(20,4),int_rece DECIMAL(20,4),inventories DECIMAL(20,4),"
                    + "non_current_assets_in_1_yr DECIMAL(20,4),other_current_assets DECIMAL(20,4),"
                    + "total_current_assets DECIMAL(20,4),equity_joint_cap DECIMAL(20,4),"
                    + "lt_receivable DECIMAL(20,4),eqt_invest DECIMAL(20,4),inv_real_estate DECIMAL(20,4),"
                    + "fix_assets_nca DECIMAL(20,4),cip DECIMAL(20,4),construction_materials DECIMAL(20,4),"
                    + "intang_assets DECIMAL(20,4),goodwill DECIMAL(20,4),lt_amort_deferred_exp DECIMAL(20,4),"
                    + "defer_tax_assets DECIMAL(20,4),other_non_current_assets DECIMAL(20,4),"
                    + "total_non_current_assets DECIMAL(20,4),total_assets DECIMAL(20,4),"
                    + "lt_borr DECIMAL(20,4),notes_payable DECIMAL(20,4),accounts_payable DECIMAL(20,4),"
                    + "accounts_payable_fin DECIMAL(20,4),prepayment_receivables DECIMAL(20,4),"
                    + "wage_payable DECIMAL(20,4),taxes_surcharges DECIMAL(20,4),other_payable DECIMAL(20,4),"
                    + "non_current_liab_in_1_yr DECIMAL(20,4),other_current_liab DECIMAL(20,4),"
                    + "total_current_liab DECIMAL(20,4),long_term_borr DECIMAL(20,4),ppayable_bonds DECIMAL(20,4),"
                    + "long_term_payable DECIMAL(20,4),specific_payable DECIMAL(20,4),estimated_liab DECIMAL(20,4),"
                    + "defer_tax_liab DECIMAL(20,4),defer_inc_non_curr_liab DECIMAL(20,4),"
                    + "other_non_current_liab DECIMAL(20,4),total_non_current_liab DECIMAL(20,4),"
                    + "total_liab DECIMAL(20,4),share_capital DECIMAL(20,4),capital_reserve DECIMAL(20,4),"
                    + "treasury_stock DECIMAL(20,4),specific_reserves DECIMAL(20,4),surplus_reserve DECIMAL(20,4),"
                    + "general_risk_reserve DECIMAL(20,4),undistributed_profit DECIMAL(20,4),"
                    + "equity_parent_company DECIMAL(20,4),minority_interest DECIMAL(20,4),"
                    + "total_equity DECIMAL(20,4),total_liab_equity DECIMAL(20,4),"
                    + "accounts_rece_decr DECIMAL(20,4),accounts_rece_fin_decr DECIMAL(20,4),"
                    + "minority_interest_inc DECIMAL(20,4),minority_interest_dec DECIMAL(20,4),"
                    + "update_flag VARCHAR(4),"
                    + "UNIQUE KEY uk_balancesheet (ts_code, end_date, report_type),"
                    + "INDEX idx_balancesheet_tscode (ts_code, end_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("cashflow", "CREATE TABLE IF NOT EXISTS cashflow ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "ts_code VARCHAR(16) NOT NULL,ann_date VARCHAR(8),f_ann_date VARCHAR(8),"
                    + "end_date VARCHAR(8) NOT NULL,report_type VARCHAR(4),comp_type VARCHAR(4),"
                    + "n_cashflow_act DECIMAL(20,4),n_cashflow_inv_act DECIMAL(20,4),"
                    + "n_cash_flows_fnc_act DECIMAL(20,4),free_cashflow DECIMAL(20,4),"
                    + "c_fr_sale_sg DECIMAL(20,4),c_fr_oth_sg DECIMAL(20,4),c_paid_goods_s DECIMAL(20,4),"
                    + "c_paid_to_for_empl DECIMAL(20,4),c_paid_for_taxes DECIMAL(20,4),c_paid_oth_op_f DECIMAL(20,4),"
                    + "c_paid_invest DECIMAL(20,4),c_paid_invest_f DECIMAL(20,4),"
                    + "c_pay_acq_const_fiolta DECIMAL(20,4),c_pay_acq_int_long_loan DECIMAL(20,4),"
                    + "disp_fix_assets_oth DECIMAL(20,4),n_invest_loss DECIMAL(20,4),"
                    + "c_fr_fnc_loan DECIMAL(20,4),c_fr_fnc_oth DECIMAL(20,4),proceeds_long_loan DECIMAL(20,4),"
                    + "c_paid_fin_fees DECIMAL(20,4),c_pay_dist_dpcp_int_exp DECIMAL(20,4),"
                    + "end_bal_cash DECIMAL(20,4),beg_bal_cash DECIMAL(20,4),n_cash_equ DECIMAL(20,4),"
                    + "n_increase_incl_child DECIMAL(20,4),prov_depr_assets DECIMAL(20,4),"
                    + "depr_fa_coga_dpba DECIMAL(20,4),amort_intang DECIMAL(20,4),"
                    + "amort_lt_deferred_exp DECIMAL(20,4),loss_disp_fa DECIMAL(20,4),loss_scr_fa DECIMAL(20,4),"
                    + "loss_fair_valu DECIMAL(20,4),fin_exp DECIMAL(20,4),loss_inv DECIMAL(20,4),"
                    + "dec_def_inc_tax_assets DECIMAL(20,4),inc_def_inc_tax_liab DECIMAL(20,4),"
                    + "dec_inv DECIMAL(20,4),dec_oper_rece DECIMAL(20,4),inc_oper_payable DECIMAL(20,4),"
                    + "net_profit DECIMAL(20,4),minority_interest DECIMAL(20,4),"
                    + "undistributed_profit_in DECIMAL(20,4),update_flag VARCHAR(4),"
                    + "UNIQUE KEY uk_cashflow (ts_code, end_date, report_type),"
                    + "INDEX idx_cashflow_tscode (ts_code, end_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("forecast", "CREATE TABLE IF NOT EXISTS forecast ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "ts_code VARCHAR(16) NOT NULL,ann_date VARCHAR(8),end_date VARCHAR(8) NOT NULL,"
                    + "type VARCHAR(16),p_change_min DECIMAL(20,4),p_change_max DECIMAL(20,4),"
                    + "net_profit_min DECIMAL(20,4),net_profit_max DECIMAL(20,4),last_parent_net DECIMAL(20,4),"
                    + "summary VARCHAR(1000),change_reason VARCHAR(2000),"
                    + "UNIQUE KEY uk_forecast (ts_code, end_date, ann_date),"
                    + "INDEX idx_forecast_tscode (ts_code, end_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
            Map.entry("express", "CREATE TABLE IF NOT EXISTS express ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "ts_code VARCHAR(16) NOT NULL,ann_date VARCHAR(8),end_date VARCHAR(8) NOT NULL,"
                    + "revenue DECIMAL(20,4),operate_profit DECIMAL(20,4),total_profit DECIMAL(20,4),"
                    + "n_income DECIMAL(20,4),total_assets DECIMAL(20,4),"
                    + "total_hldr_eqy_exc_min_int DECIMAL(20,4),basic_eps DECIMAL(20,4),diluted_eps DECIMAL(20,4),"
                    + "growth_yield DECIMAL(20,4),or_growth_yield DECIMAL(20,4),"
                    + "yst_net_profit DECIMAL(20,4),bm_net_profit DECIMAL(20,4),bm_growth_sales DECIMAL(20,4),"
                    + "update_flag VARCHAR(4),"
                    + "UNIQUE KEY uk_express (ts_code, end_date),"
                    + "INDEX idx_express_tscode (ts_code, end_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    );

    private static final Map<String, String> CREATE_TABLE_SQL_SQLITE = Map.ofEntries(
            Map.entry("stock_basic", "CREATE TABLE IF NOT EXISTS stock_basic ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts_code TEXT NOT NULL UNIQUE,"
                    + "symbol TEXT,name TEXT,area TEXT,industry TEXT,fullname TEXT,enname TEXT,"
                    + "cnspell TEXT,market TEXT,exchange TEXT,curr_type TEXT,list_status TEXT,"
                    + "list_date TEXT,delist_date TEXT,is_hs TEXT,act_name TEXT,act_ent_type TEXT)"),
            Map.entry("trade_cal", "CREATE TABLE IF NOT EXISTS trade_cal ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "exchange TEXT,cal_date TEXT NOT NULL,is_open TEXT,pretrade_date TEXT,"
                    + "is_first_of_week INTEGER DEFAULT 0,is_last_of_week INTEGER DEFAULT 0,"
                    + "is_first_of_month INTEGER DEFAULT 0,is_last_of_month INTEGER DEFAULT 0,"
                    + "is_first_of_quarter INTEGER DEFAULT 0,is_last_of_quarter INTEGER DEFAULT 0,"
                    + "UNIQUE(exchange, cal_date))"),
            Map.entry("daily_quote", "CREATE TABLE IF NOT EXISTS daily_quote ("
                    + "ts_code TEXT NOT NULL,trade_date TEXT NOT NULL,"
                    + "open REAL,high REAL,low REAL,close REAL,pre_close REAL,change_amt REAL,"
                    + "pct_chg REAL,vol REAL,amount REAL,"
                    + "PRIMARY KEY (ts_code, trade_date))"),
            Map.entry("adj_factor", "CREATE TABLE IF NOT EXISTS adj_factor ("
                    + "ts_code TEXT NOT NULL,trade_date TEXT NOT NULL,adj_factor REAL,"
                    + "PRIMARY KEY (ts_code, trade_date))"),
            Map.entry("dividend", "CREATE TABLE IF NOT EXISTS dividend ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts_code TEXT NOT NULL,end_date TEXT,ann_date TEXT,div_proc TEXT,"
                    + "stk_div REAL,stk_bo_rate REAL,stk_co_rate REAL,cash_div REAL,cash_div_tax REAL,"
                    + "record_date TEXT,ex_date TEXT,pay_date TEXT,div_listdate TEXT,imp_ann_date TEXT,"
                    + "base_date TEXT,base_share REAL,"
                    + "UNIQUE(ts_code, end_date, ann_date))"),
            Map.entry("index_weight", "CREATE TABLE IF NOT EXISTS index_weight ("
                    + "ts_code TEXT NOT NULL,trade_date TEXT NOT NULL,"
                    + "con_code TEXT NOT NULL,weight REAL,"
                    + "PRIMARY KEY (ts_code, trade_date, con_code))"),
            Map.entry("sw_industry", "CREATE TABLE IF NOT EXISTS sw_industry ("
                    + "index_code TEXT NOT NULL,index_name TEXT,"
                    + "level INTEGER,parent_code TEXT,"
                    + "src TEXT NOT NULL DEFAULT 'SWS2021',"
                    + "PRIMARY KEY (index_code, src))"),
            Map.entry("sw_industry_member", "CREATE TABLE IF NOT EXISTS sw_industry_member ("
                    + "ts_code TEXT NOT NULL,index_code TEXT NOT NULL,"
                    + "index_name TEXT,in_date TEXT,out_date TEXT,"
                    + "is_new TEXT,src TEXT NOT NULL DEFAULT 'SWS2021',update_date TEXT NOT NULL,"
                    + "PRIMARY KEY (ts_code, index_code, update_date))"),
            Map.entry("screen_plan", "CREATE TABLE IF NOT EXISTS screen_plan ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name VARCHAR(128) NOT NULL,description VARCHAR(512),screen_config TEXT NOT NULL,"
                    + "created_at VARCHAR(32),updated_at VARCHAR(32))"),
            Map.entry("screen_result", "CREATE TABLE IF NOT EXISTS screen_result ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "plan_id INTEGER NOT NULL,screen_date VARCHAR(8) NOT NULL,total_count INTEGER,"
                    + "stocks_json TEXT,params_json TEXT,created_at VARCHAR(32))"),
            Map.entry("screen_lock", "CREATE TABLE IF NOT EXISTS screen_lock ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "result_id INTEGER,plan_id INTEGER,lock_date VARCHAR(8),stocks_json TEXT,"
                    + "ret_5d REAL,ret_10d REAL,ret_20d REAL,"
                    + "benchmark_ret_5d REAL,benchmark_ret_10d REAL,benchmark_ret_20d REAL,"
                    + "status VARCHAR(16),created_at VARCHAR(32),updated_at VARCHAR(32))"),
            Map.entry("stock_namechange", "CREATE TABLE IF NOT EXISTS stock_namechange ("
                    + "ts_code TEXT NOT NULL,name TEXT,"
                    + "start_date TEXT,end_date TEXT,change_reason TEXT,"
                    + "PRIMARY KEY (ts_code, start_date))"),
            Map.entry("stock_suspend_d", "CREATE TABLE IF NOT EXISTS stock_suspend_d ("
                    + "ts_code TEXT NOT NULL,trade_date TEXT NOT NULL,"
                    + "susp_reason TEXT,resump_date TEXT,"
                    + "PRIMARY KEY (ts_code, trade_date))"),
            Map.entry("stock_stk_limit", "CREATE TABLE IF NOT EXISTS stock_stk_limit ("
                    + "ts_code TEXT NOT NULL,trade_date TEXT NOT NULL,"
                    + "pre_close REAL,up_limit REAL,down_limit REAL,"
                    + "PRIMARY KEY (ts_code, trade_date))"),
            Map.entry("income", "CREATE TABLE IF NOT EXISTS income ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts_code TEXT NOT NULL,ann_date TEXT,f_ann_date TEXT,"
                    + "end_date TEXT NOT NULL,report_type TEXT,comp_type TEXT,"
                    + "basic_eps REAL,diluted_eps REAL,total_revenue REAL,revenue REAL,"
                    + "total_cogs REAL,operate_cost REAL,operate_profit REAL,non_oper_income REAL,non_oper_exp REAL,"
                    + "total_profit REAL,n_income REAL,n_income_attr_p REAL,minority_interest REAL,"
                    + "adjust_profit REAL,income_tax REAL,n_income_yoy REAL,dt_profit_yoy REAL,"
                    + "sell_exp REAL,admin_exp REAL,financial_exp REAL,rd_exp REAL,"
                    + "impair_end_invest REAL,impair_end_oper REAL,invest_income REAL,"
                    + "invest_income_inc REAL,invest_income_dec REAL,fairvalue_change_income REAL,"
                    + "exchange_gain REAL,asset_dispose_income REAL,other_income REAL,operate_n_income REAL,"
                    + "credit_impair_loss REAL,asset_impair_loss REAL,bbit REAL,bbit_yoy REAL,"
                    + "operate_profit_income_yoy REAL,update_flag TEXT,"
                    + "UNIQUE (ts_code, end_date, report_type))"),
            Map.entry("balancesheet", "CREATE TABLE IF NOT EXISTS balancesheet ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts_code TEXT NOT NULL,ann_date TEXT,f_ann_date TEXT,"
                    + "end_date TEXT NOT NULL,report_type TEXT,comp_type TEXT,"
                    + "monetary_funds REAL,accounts_rece REAL,notes_rece REAL,accounts_rece_fin REAL,"
                    + "other_rece REAL,prepayment REAL,dividends_rece REAL,int_rece REAL,inventories REAL,"
                    + "non_current_assets_in_1_yr REAL,other_current_assets REAL,total_current_assets REAL,"
                    + "equity_joint_cap REAL,lt_receivable REAL,eqt_invest REAL,inv_real_estate REAL,"
                    + "fix_assets_nca REAL,cip REAL,construction_materials REAL,intang_assets REAL,goodwill REAL,"
                    + "lt_amort_deferred_exp REAL,defer_tax_assets REAL,other_non_current_assets REAL,"
                    + "total_non_current_assets REAL,total_assets REAL,lt_borr REAL,notes_payable REAL,"
                    + "accounts_payable REAL,accounts_payable_fin REAL,prepayment_receivables REAL,"
                    + "wage_payable REAL,taxes_surcharges REAL,other_payable REAL,"
                    + "non_current_liab_in_1_yr REAL,other_current_liab REAL,total_current_liab REAL,"
                    + "long_term_borr REAL,ppayable_bonds REAL,long_term_payable REAL,specific_payable REAL,"
                    + "estimated_liab REAL,defer_tax_liab REAL,defer_inc_non_curr_liab REAL,"
                    + "other_non_current_liab REAL,total_non_current_liab REAL,total_liab REAL,"
                    + "share_capital REAL,capital_reserve REAL,treasury_stock REAL,specific_reserves REAL,"
                    + "surplus_reserve REAL,general_risk_reserve REAL,undistributed_profit REAL,"
                    + "equity_parent_company REAL,minority_interest REAL,total_equity REAL,total_liab_equity REAL,"
                    + "accounts_rece_decr REAL,accounts_rece_fin_decr REAL,minority_interest_inc REAL,"
                    + "minority_interest_dec REAL,update_flag TEXT,"
                    + "UNIQUE (ts_code, end_date, report_type))"),
            Map.entry("cashflow", "CREATE TABLE IF NOT EXISTS cashflow ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts_code TEXT NOT NULL,ann_date TEXT,f_ann_date TEXT,"
                    + "end_date TEXT NOT NULL,report_type TEXT,comp_type TEXT,"
                    + "n_cashflow_act REAL,n_cashflow_inv_act REAL,n_cash_flows_fnc_act REAL,free_cashflow REAL,"
                    + "c_fr_sale_sg REAL,c_fr_oth_sg REAL,c_paid_goods_s REAL,c_paid_to_for_empl REAL,"
                    + "c_paid_for_taxes REAL,c_paid_oth_op_f REAL,c_paid_invest REAL,c_paid_invest_f REAL,"
                    + "c_pay_acq_const_fiolta REAL,c_pay_acq_int_long_loan REAL,disp_fix_assets_oth REAL,"
                    + "n_invest_loss REAL,c_fr_fnc_loan REAL,c_fr_fnc_oth REAL,proceeds_long_loan REAL,"
                    + "c_paid_fin_fees REAL,c_pay_dist_dpcp_int_exp REAL,end_bal_cash REAL,beg_bal_cash REAL,"
                    + "n_cash_equ REAL,n_increase_incl_child REAL,prov_depr_assets REAL,depr_fa_coga_dpba REAL,"
                    + "amort_intang REAL,amort_lt_deferred_exp REAL,loss_disp_fa REAL,loss_scr_fa REAL,"
                    + "loss_fair_valu REAL,fin_exp REAL,loss_inv REAL,dec_def_inc_tax_assets REAL,"
                    + "inc_def_inc_tax_liab REAL,dec_inv REAL,dec_oper_rece REAL,inc_oper_payable REAL,"
                    + "net_profit REAL,minority_interest REAL,undistributed_profit_in REAL,update_flag TEXT,"
                    + "UNIQUE (ts_code, end_date, report_type))"),
            Map.entry("forecast", "CREATE TABLE IF NOT EXISTS forecast ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts_code TEXT NOT NULL,ann_date TEXT,end_date TEXT NOT NULL,"
                    + "type TEXT,p_change_min REAL,p_change_max REAL,"
                    + "net_profit_min REAL,net_profit_max REAL,last_parent_net REAL,"
                    + "summary TEXT,change_reason TEXT,"
                    + "UNIQUE (ts_code, end_date, ann_date))"),
            Map.entry("express", "CREATE TABLE IF NOT EXISTS express ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts_code TEXT NOT NULL,ann_date TEXT,end_date TEXT NOT NULL,"
                    + "revenue REAL,operate_profit REAL,total_profit REAL,"
                    + "n_income REAL,total_assets REAL,"
                    + "total_hldr_eqy_exc_min_int REAL,basic_eps REAL,diluted_eps REAL,"
                    + "growth_yield REAL,or_growth_yield REAL,"
                    + "yst_net_profit REAL,bm_net_profit REAL,bm_growth_sales REAL,"
                    + "update_flag TEXT,"
                    + "UNIQUE (ts_code, end_date))")
    );

    private Map<String, String> getCreateTableSql() {
        return "sqlite".equalsIgnoreCase(dbType) ? CREATE_TABLE_SQL_SQLITE : CREATE_TABLE_SQL_MYSQL;
    }

    private void clearSelectedData(List<InitStep> steps) {
        updateStep("重建数据表");
        Map<String, String> createTableSql = getCreateTableSql();
        for (InitStep step : steps) {
            String table = step.getTableName();
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
            jdbcTemplate.execute(createTableSql.get(table));
            log.info("Recreated table: {}", table);
        }
    }

    private void clearKlineCache() {
        org.springframework.cache.Cache klineCache = cacheManager.getCache("kline");
        if (klineCache != null) {
            klineCache.clear();
            log.info("Kline cache cleared");
        }
    }

    private void reportProgress(String stepName, int processed, int total) {
        if (processed % 100 == 0 || processed == total) {
            progressRef.updateAndGet(p -> p.toBuilder()
                    .processedStocks(processed)
                    .currentStep(stepName + " (" + processed + "/" + total + ")")
                    .build());
            log.info("{} progress: {}/{}", stepName, processed, total);
        }
    }

    private void updateStep(String step) {
        progressRef.updateAndGet(p -> p.toBuilder().currentStep(step).build());
    }

    private List<InitStep> parseSteps(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return new ArrayList<>(EXECUTION_ORDER);
        }
        List<InitStep> parsed = new ArrayList<>();
        for (String code : steps) {
            InitStep step = InitStep.fromCode(code);
            if (step == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "无效的初始化步骤: " + code + "，可选值: " +
                                Arrays.stream(InitStep.values()).map(InitStep::getCode).collect(Collectors.joining(", ")));
            }
            if (!parsed.contains(step)) {
                parsed.add(step);
            }
        }
        return parsed;
    }
}
