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
            InitStep.STOCK_BASIC, InitStep.TRADE_CAL, InitStep.DAILY, InitStep.ADJ_FACTOR, InitStep.DIVIDEND);

    private final JdbcTemplate jdbcTemplate;
    private final StockBasicService stockBasicService;
    private final TradeCalService tradeCalService;
    private final DailyQuoteService dailyQuoteService;
    private final AdjFactorService adjFactorService;
    private final DividendService dividendService;
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
                case DAILY -> executeDaily(stocks);
                case ADJ_FACTOR -> executeAdjFactor(stocks);
                case DIVIDEND -> executeDividend(stocks);
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
        String startDate = LocalDate.now().minusYears(30).format(DATE_FMT);
        String endDate = LocalDate.now().format(DATE_FMT);
        tradeCalService.fetchAndSaveTradeCal(null, startDate, endDate);
        log.info("Trade calendar synced");
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
                    + "status VARCHAR(16),created_at VARCHAR(32),updated_at VARCHAR(32)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
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
                    + "status VARCHAR(16),created_at VARCHAR(32),updated_at VARCHAR(32))")
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
