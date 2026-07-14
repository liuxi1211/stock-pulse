package com.arthur.stock.task;

import com.arthur.stock.service.IndexWeightService;
import com.arthur.stock.service.TradeCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 指数成分股权重定时同步任务：每交易日 20:00 盘后同步最新交易日快照。
 * <p>
 * 成分股每年 6/12 月定期调样 + 临时事件调整，需每日增量同步；
 * 幂等（同主键覆盖），重复执行无副作用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexWeightTask {

    private static final List<String> INDEX_CODES = List.of(
            "000300.SH", "000905.SH", "000016.SH", "000852.SH");

    private final IndexWeightService indexWeightService;
    private final TradeCalendarService tradeCalendarService;

    @Scheduled(cron = "0 0 20 * * MON-FRI")
    public void syncDaily() {
        log.info("===== IndexWeightTask start =====");
        String tradeDate = tradeCalendarService.getLatestTradeDate();
        if (tradeDate == null) {
            log.warn("IndexWeightTask 跳过：无法获取最新交易日");
            return;
        }
        for (String indexCode : INDEX_CODES) {
            try {
                int n = indexWeightService.fetchAndSave(indexCode, tradeDate);
                log.info("IndexWeightTask synced: {} @ {} ({} records)", indexCode, tradeDate, n);
            } catch (Exception e) {
                log.error("IndexWeightTask 同步失败 {}: {}", indexCode, e.getMessage(), e);
            }
        }
        log.info("===== IndexWeightTask done =====");
    }
}
