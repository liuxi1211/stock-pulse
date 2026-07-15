package com.arthur.stock.task;

import com.arthur.stock.service.SwIndustryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 申万行业分类定时同步任务：每年 1 月、7 月 1 日 22:00 全量同步（SWS2021）。
 * <p>
 * 申万行业分类调整频率较低（约半年一次），半年同步一次足够；
 * 分类与成分股一并刷新，幂等（按业务键先删后插），重复执行无副作用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwIndustryTask {

    private final SwIndustryService swIndustryService;

    @Scheduled(cron = "0 0 22 1 1,7 *")
    public void syncHalfYearly() {
        log.info("===== SwIndustryTask start =====");
        try {
            int classify = swIndustryService.fetchAndSaveClassify("SWS2021");
            log.info("SwIndustryTask classify synced: {} industries", classify);
        } catch (Exception e) {
            log.error("SwIndustryTask classify sync failed: {}", e.getMessage(), e);
        }
        try {
            int members = swIndustryService.fetchAndSaveAllMembers("SWS2021");
            log.info("SwIndustryTask members synced: {} records", members);
        } catch (Exception e) {
            log.error("SwIndustryTask members sync failed: {}", e.getMessage(), e);
        }
        log.info("===== SwIndustryTask done =====");
    }
}
