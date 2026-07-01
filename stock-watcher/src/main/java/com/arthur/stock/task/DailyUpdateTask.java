package com.arthur.stock.task;

import com.arthur.stock.service.DailyQuoteService;
import com.arthur.stock.service.DividendService;
import com.arthur.stock.service.StockBasicService;
import com.arthur.stock.service.TradeCalService;
import com.arthur.stock.service.AdjFactorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 每日数据更新定时任务
 * <p>
 * 每天下午 16:00 执行：
 * 1. 从 Tushare 拉取交易日历，与本地比对后增删改
 * 2. 从 Tushare 拉取股票基础信息，与本地比对后增删改
 * 3. 拉取当天全市场日线行情并保存
 * 4. 拉取当天全市场复权因子并保存
 * 5. 拉取当天公告的分红送股数据并保存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyUpdateTask {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TradeCalService tradeCalService;
    private final StockBasicService stockBasicService;
    private final DailyQuoteService dailyQuoteService;
    private final AdjFactorService adjFactorService;
    private final DividendService dividendService;
    private final CacheManager cacheManager;

    /**
     * 每天下午 16:00 执行日常数据更新
     */
    @Scheduled(cron = "0 0 16 * * ?")
    public void dailyUpdate() {
        log.info("===== Daily update task started =====");

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

        invalidateKlineCache();

        log.info("===== Daily update task finished =====");
    }

    /**
     * 同步当日交易日历数据
     */
    private void updateTradeCal() {
        String today = LocalDate.now().format(DATE_FMT);
        log.info("[Step 1] Syncing trade_cal data for {}", today);
        tradeCalService.fetchAndSaveTradeCal(null, today, today);
    }

    /**
     * 同步股票基础信息数据
     */
    private void updateStockBasic() {
        log.info("[Step 2] Syncing stock_basic data");
        stockBasicService.fetchAndSaveStockBasic();
    }

    /**
     * 拉取当日全市场日线行情并保存
     */
    private void updateDailyQuotes() {
        String tradeDate = LocalDate.now().format(DATE_FMT);
        log.info("[Step 3] Fetching daily quotes for {}", tradeDate);
        dailyQuoteService.fetchAndSaveByTradeDate(tradeDate);
    }

    /**
     * 拉取当日全市场复权因子并保存
     */
    private void updateAdjFactor() {
        String tradeDate = LocalDate.now().format(DATE_FMT);
        log.info("[Step 4] Fetching adj_factor for {}", tradeDate);
        adjFactorService.fetchAndSaveByTradeDate(tradeDate);
    }

    /**
     * 拉取当日公告的分红送股数据并保存
     */
    private void updateDividend() {
        String today = LocalDate.now().format(DATE_FMT);
        log.info("[Step 5] Fetching dividend for ann_date={}", today);
        dividendService.fetchAndSaveByAnnDate(today);
    }

    /**
     * 每日数据更新完成后清除kline缓存，确保下次查询时用最新数据重新计算
     */
    private void invalidateKlineCache() {
        org.springframework.cache.Cache klineCache = cacheManager.getCache("kline");
        if (klineCache != null) {
            klineCache.clear();
            log.info("[Cache] kline cache cleared after daily update");
        }
    }
}
