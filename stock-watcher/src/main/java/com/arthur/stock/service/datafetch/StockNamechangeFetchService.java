package com.arthur.stock.service.datafetch;

import com.arthur.stock.service.StockNamechangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 更名（ST 戴帽摘帽）数据拉取服务。
 * <ul>
 *   <li>{@link #dailyIncremental()}：每日 16:30 增量（属于 16:30 批次）。</li>
 *   <li>{@link #quarterlyFull()}：每季度首月 1 号全量（独立定时触发）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockNamechangeFetchService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockNamechangeService stockNamechangeService;

    /** 每日 16:30 增量 */
    public void dailyIncremental() {
        String today = LocalDate.now().format(DATE_FMT);
        log.info("===== StockNamechangeFetchService daily start: {} =====", today);
        try {
            int n = stockNamechangeService.fetchAndSaveIncremental(today);
            log.info("StockNamechangeFetchService daily done: {} records", n);
        } catch (Exception e) {
            log.error("StockNamechangeFetchService daily failed", e);
        }
    }

    /** 每季度首月 1 号全量 */
    public void quarterlyFull() {
        log.info("===== StockNamechangeFetchService full start =====");
        try {
            int n = stockNamechangeService.fetchAndSaveAll();
            log.info("StockNamechangeFetchService full done: {} records", n);
        } catch (Exception e) {
            log.error("StockNamechangeFetchService full failed", e);
        }
    }
}
