package com.arthur.stock.task;

import com.arthur.stock.service.IndexBasicService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 指数基本信息定时同步任务：每交易日 16:25 盘后全量同步（先于 index_daily 16:30）。
 * <p>
 * index_basic 是低频变更的维度表，全量替换（deleteAll + insertBatch），幂等。
 * 作为 index_daily / index_weight 同步的「指数代码主数据源」，必须先于二者执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexBasicTask {

    private final IndexBasicService indexBasicService;

    @Scheduled(cron = "0 25 16 * * MON-FRI")
    public void syncDaily() {
        log.info("===== IndexBasicTask start =====");
        try {
            int n = indexBasicService.fetchAndSaveAll();
            log.info("===== IndexBasicTask done: {} records =====", n);
        } catch (Exception e) {
            log.error("IndexBasicTask 同步失败: {}", e.getMessage(), e);
        }
    }
}
