package com.arthur.stock.task;

import com.arthur.stock.cache.DataSourceHealthCache;
import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.governance.DatasourceVO;
import com.arthur.stock.dto.tushare.TradeCalQueryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 数据源连通性定时检测任务：每小时执行一次
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceHealthJob {

    private final TushareClient tushareClient;
    private final DataSourceHealthCache healthCache;

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Scheduled(cron = "0 0 * * * ?")
    public void testConnectivity() {
        log.debug("开始数据源连通性检测...");
        DatasourceVO.DatasourceVOBuilder builder = DatasourceVO.builder()
                .sourceCode("TUSHARE")
                .sourceName("Tushare Pro")
                .testInterface("trade_cal（交易日历）")
                .lastTestTime(LocalDateTime.now().format(DATETIME_FMT));

        long startTime = System.currentTimeMillis();
        try {
            String today = LocalDate.now().format(DATE_FMT);
            TradeCalQueryDTO query = TradeCalQueryDTO.builder()
                    .exchange("SSE")
                    .startDate(today)
                    .endDate(today)
                    .build();
            tushareClient.tradeCal(query);

            long responseTime = System.currentTimeMillis() - startTime;
            builder.status("ACTIVE")
                    .lastTestOk(true)
                    .responseTimeMs(responseTime);
            log.debug("数据源连通性检测成功, 响应时间={}ms", responseTime);
        } catch (Exception e) {
            builder.status("INACTIVE")
                    .lastTestOk(false)
                    .responseTimeMs(System.currentTimeMillis() - startTime);
            log.warn("数据源连通性检测失败: {}", e.getMessage());
        }

        healthCache.update(builder.build());
    }
}
