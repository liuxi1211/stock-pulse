package com.arthur.stock.service.datafetch;

import com.arthur.stock.constant.ExchangeEnum;
import com.arthur.stock.service.AdjFactorService;
import com.arthur.stock.service.DailyQuoteService;
import com.arthur.stock.service.DividendService;
import com.arthur.stock.service.IndexBasicService;
import com.arthur.stock.service.StockBasicService;
import com.arthur.stock.service.TradeCalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 16:00 批次数据拉取服务：交易日历 / 股票基础 / 日线行情 / 复权因子 / 分红 / 指数基础 / kline 缓存失效。
 * <p>
 * 由 {@code DataFetchTriggerEventListener} 在收到 batchKey="1600" 事件后调用，
 * 不再持有 @Scheduled / @ManagedTask，调度入口在 task 包。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyFetchService1600 {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TradeCalService tradeCalService;
    private final StockBasicService stockBasicService;
    private final DailyQuoteService dailyQuoteService;
    private final AdjFactorService adjFactorService;
    private final DividendService dividendService;
    private final IndexBasicService indexBasicService;
    private final CacheManager cacheManager;

    /**
     * 执行 16:00 批次全部拉取，返回是否出现错误（用于批次聚合上报）。
     */
    public boolean fetchAll(String tradeDate) {
        log.info("===== DailyFetchService1600 start, tradeDate={} =====", tradeDate);
        try {
            try {
                updateTradeCal();
            } catch (Exception e) {
                log.error("Failed to update trade_cal", e);
            }

            try {
                updateStockBasic();
            } catch (Exception e) {
                log.error("Failed to update stock_basic", e);
            }

            try {
                updateDailyQuotes();
            } catch (Exception e) {
                log.error("Failed to update daily quotes", e);
            }

            try {
                updateAdjFactor();
            } catch (Exception e) {
                log.error("Failed to update adj_factor", e);
            }

            try {
                updateDividend();
            } catch (Exception e) {
                log.error("Failed to update dividend", e);
            }

            try {
                updateIndexBasic();
            } catch (Exception e) {
                log.error("Failed to update index_basic", e);
            }

            invalidateKlineCache();

            log.info("===== DailyFetchService1600 finished =====");
            return false;
        } catch (Exception e) {
            log.error("[DailyFetchService1600] tradeDate={} 执行失败", tradeDate, e);
            return true;
        }
    }

    private void updateTradeCal() {
        String today = LocalDate.now().format(DATE_FMT);
        log.info("[Step 1] Syncing trade_cal data for {}", today);
        for (ExchangeEnum ex : List.of(ExchangeEnum.SSE, ExchangeEnum.SZSE)) {
            try {
                tradeCalService.fetchAndSaveTradeCal(ex.getCode(), today, today);
            } catch (Exception e) {
                log.error("Failed to update trade_cal for {}", ex.getCode(), e);
            }
        }
    }

    private void updateStockBasic() {
        log.info("[Step 2] Syncing stock_basic data");
        stockBasicService.fetchAndSaveStockBasic();
    }

    private void updateDailyQuotes() {
        String tradeDate = LocalDate.now().format(DATE_FMT);
        log.info("[Step 3] Fetching daily quotes for {}", tradeDate);
        dailyQuoteService.fetchAndSaveByTradeDate(tradeDate);
    }

    private void updateAdjFactor() {
        String tradeDate = LocalDate.now().format(DATE_FMT);
        log.info("[Step 4] Fetching adj_factor for {}", tradeDate);
        adjFactorService.fetchAndSaveByTradeDate(tradeDate);
    }

    private void updateDividend() {
        String today = LocalDate.now().format(DATE_FMT);
        log.info("[Step 5] Fetching dividend for ann_date={}", today);
        dividendService.fetchAndSaveByAnnDate(today);
    }

    private void updateIndexBasic() {
        log.info("[Step 6] Syncing index_basic data");
        indexBasicService.fetchAndSaveAll();
    }

    private void invalidateKlineCache() {
        org.springframework.cache.Cache klineCache = cacheManager.getCache("kline");
        if (klineCache != null) {
            klineCache.clear();
            log.info("[Cache] kline cache cleared after daily update");
        }
    }
}
