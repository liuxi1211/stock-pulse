package com.arthur.stock.service.impl;

import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.DataInitProgress;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            throw new BusinessException(ErrorCode.CONFLICT, "数据初始化正在进行中，请勿重复执行");
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
            throw new BusinessException(ErrorCode.STOCK_NOT_FOUND, "本地无股票基础信息，请先初始化 stock_basic 步骤");
        }
        return local;
    }

    private static final Map<String, String> CREATE_TABLE_SQL = Map.of(
            "stock_basic", "CREATE TABLE IF NOT EXISTS stock_basic ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts_code TEXT NOT NULL UNIQUE,"
                    + "symbol TEXT,name TEXT,area TEXT,industry TEXT,fullname TEXT,enname TEXT,"
                    + "cnspell TEXT,market TEXT,exchange TEXT,curr_type TEXT,list_status TEXT,"
                    + "list_date TEXT,delist_date TEXT,is_hs TEXT,act_name TEXT,act_ent_type TEXT)",
            "trade_cal", "CREATE TABLE IF NOT EXISTS trade_cal ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "exchange TEXT,cal_date TEXT NOT NULL,is_open TEXT,pretrade_date TEXT,"
                    + "UNIQUE(exchange, cal_date))",
            "daily_quote", "CREATE TABLE IF NOT EXISTS daily_quote ("
                    + "ts_code TEXT NOT NULL,trade_date TEXT NOT NULL,"
                    + "open REAL,high REAL,low REAL,close REAL,pre_close REAL,change_amt REAL,"
                    + "pct_chg REAL,vol REAL,amount REAL,"
                    + "PRIMARY KEY (ts_code, trade_date))",
            "adj_factor", "CREATE TABLE IF NOT EXISTS adj_factor ("
                    + "ts_code TEXT NOT NULL,trade_date TEXT NOT NULL,adj_factor REAL,"
                    + "PRIMARY KEY (ts_code, trade_date))",
            "dividend", "CREATE TABLE IF NOT EXISTS dividend ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts_code TEXT NOT NULL,end_date TEXT,ann_date TEXT,div_proc TEXT,"
                    + "stk_div REAL,stk_bo_rate REAL,stk_co_rate REAL,cash_div REAL,cash_div_tax REAL,"
                    + "record_date TEXT,ex_date TEXT,pay_date TEXT,div_listdate TEXT,imp_ann_date TEXT,"
                    + "base_date TEXT,base_share REAL,"
                    + "UNIQUE(ts_code, end_date, ann_date))"
    );

    private void clearSelectedData(List<InitStep> steps) {
        updateStep("重建数据表");
        for (InitStep step : steps) {
            String table = step.getTableName();
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
            jdbcTemplate.execute(CREATE_TABLE_SQL.get(table));
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
