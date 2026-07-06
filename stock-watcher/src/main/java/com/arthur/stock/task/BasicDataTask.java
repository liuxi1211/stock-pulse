package com.arthur.stock.task;

import com.arthur.stock.service.BasicDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 基本面数据定时任务：
 * <ul>
 *   <li>每日 16:10 拉取当日全市场 daily_basic（估值/换手率/市值）；</li>
 *   <li>每周日 17:00 拉取最近 2 年 fina_indicator（财务指标，按股票逐只拉）。</li>
 * </ul>
 * <p>
 * daily_basic 在 DailyUpdateTask（16:00）之后执行，确保 trade_date 当日数据已就绪。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BasicDataTask {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final BasicDataService basicDataService;

    /** 每日 16:10 拉取当日 daily_basic */
    @Scheduled(cron = "0 10 16 * * MON-FRI")
    public void fetchDailyBasic() {
        String tradeDate = LocalDate.now().format(DATE_FMT);
        log.info("===== BasicDataTask daily_basic start, tradeDate={} =====", tradeDate);
        try {
            int n = basicDataService.fetchAndSaveDailyBasic(tradeDate);
            log.info("===== BasicDataTask daily_basic done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataTask daily_basic 失败 tradeDate={}", tradeDate, e);
        }
    }

    /** 每周日 17:00 拉取最近 2 年 fina_indicator */
    @Scheduled(cron = "0 0 17 * * SUN")
    public void fetchFinaIndicator() {
        String endPeriod = LocalDate.now().format(DATE_FMT);
        String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
        log.info("===== BasicDataTask fina_indicator start, [{}~{}] =====", startPeriod, endPeriod);
        try {
            int n = basicDataService.fetchAndSaveFinaIndicator(startPeriod, endPeriod);
            log.info("===== BasicDataTask fina_indicator done, saved={} =====", n);
        } catch (Exception e) {
            log.error("BasicDataTask fina_indicator 失败", e);
        }
    }
}
