package com.arthur.stock.service.datafetch;

import com.arthur.stock.constant.InitStep;
import com.arthur.stock.service.DataInitService;
import com.arthur.stock.service.StockStkLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 涨跌停价数据拉取服务。
 * <ul>
 *   <li>{@link #dailyIncremental()}：每日 16:40 增量（属于 16:40 批次）。</li>
 *   <li>{@link #monthlyFull()}：每月 1 号全量（独立定时触发，委托 DataInitService 按月迭代）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockStkLimitFetchService {

    private final StockStkLimitService stockStkLimitService;
    private final DataInitService dataInitService;

    /** 每日 16:40 增量 */
    public void dailyIncremental() {
        log.info("===== StockStkLimitFetchService daily start =====");
        try {
            int n = stockStkLimitService.fetchAndSaveIncremental();
            log.info("StockStkLimitFetchService daily done: {} records", n);
        } catch (Exception e) {
            log.error("StockStkLimitFetchService daily failed", e);
        }
    }

    /** 每月 1 号全量（委托 DataInitService 按月迭代拉取） */
    public void monthlyFull() {
        log.info("===== StockStkLimitFetchService full start =====");
        try {
            String taskId = dataInitService.fullRebuild(InitStep.STK_LIMIT.getCode(), "SYSTEM");
            log.info("StockStkLimitFetchService full triggered: taskId={}", taskId);
        } catch (Exception e) {
            log.error("StockStkLimitFetchService full failed", e);
        }
    }
}
