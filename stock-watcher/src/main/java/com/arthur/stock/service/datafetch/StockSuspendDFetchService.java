package com.arthur.stock.service.datafetch;

import com.arthur.stock.service.StockSuspendDService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 停复牌信息数据拉取服务。
 * <ul>
 *   <li>{@link #dailyIncremental()}：每日 16:40 增量（属于 16:40 批次）。</li>
 *   <li>{@link #monthlyFull()}：每月 1 号全量（独立定时触发）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockSuspendDFetchService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockSuspendDService stockSuspendDService;

    /** 每日 16:40 增量 */
    public void dailyIncremental() {
        String today = LocalDate.now().format(DATE_FMT);
        log.info("===== StockSuspendDFetchService daily start: {} =====", today);
        try {
            int n = stockSuspendDService.fetchAndSaveIncremental(today);
            log.info("StockSuspendDFetchService daily done: {} records", n);
        } catch (Exception e) {
            log.error("StockSuspendDFetchService daily failed", e);
        }
    }

    /** 每月 1 号全量 */
    public void monthlyFull() {
        log.info("===== StockSuspendDFetchService full start =====");
        try {
            int n = stockSuspendDService.fetchAndSaveAll();
            log.info("StockSuspendDFetchService full done: {} records", n);
        } catch (Exception e) {
            log.error("StockSuspendDFetchService full failed", e);
        }
    }
}
