package com.arthur.stock.service.datafetch;

import com.arthur.stock.constant.SwIndustryConstants;
import com.arthur.stock.service.SwIndustryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 申万行业分类拉取服务（每年 1 月、7 月 1 日全量同步）。
 * <p>
 * 分类与成分股一并刷新，幂等（按业务键先删后插），重复执行无副作用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwIndustryFetchService {

    private final SwIndustryService swIndustryService;

    public void syncHalfYearly() {
        log.info("===== SwIndustryFetchService start =====");
        try {
            int classify = swIndustryService.fetchAndSaveClassify(SwIndustryConstants.SW_SRC);
            log.info("SwIndustryFetchService classify synced: {} industries", classify);
        } catch (Exception e) {
            log.error("SwIndustryFetchService classify sync failed: {}", e.getMessage(), e);
        }
        try {
            int members = swIndustryService.fetchAndSaveAllMembers(SwIndustryConstants.SW_SRC);
            log.info("SwIndustryFetchService members synced: {} records", members);
        } catch (Exception e) {
            log.error("SwIndustryFetchService members sync failed: {}", e.getMessage(), e);
        }
        log.info("===== SwIndustryFetchService done =====");
    }
}
