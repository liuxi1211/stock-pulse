package com.arthur.stock.service.datafetch;

import com.arthur.stock.constant.IndexConstants;
import com.arthur.stock.mapper.IndexBasicMapper;
import com.arthur.stock.service.IndexWeightService;
import com.arthur.stock.service.TradeCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 指数成分股权重拉取服务（每日 20:00 批次）。
 * <p>
 * 同步的指数列表从 index_basic 表动态读取全部指数；index_basic 为空时回退到 INDEX_WEIGHT_CODES。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexWeightFetchService {

    private final IndexWeightService indexWeightService;
    private final IndexBasicMapper indexBasicMapper;
    private final TradeCalendarService tradeCalendarService;

    public void syncDaily() {
        log.info("===== IndexWeightFetchService start =====");
        String tradeDate = tradeCalendarService.getLatestTradeDate();
        if (tradeDate == null) {
            log.warn("IndexWeightFetchService 跳过：无法获取最新交易日");
            return;
        }
        List<String> indexCodes = indexBasicMapper.selectAllTsCodes();
        if (indexCodes.isEmpty()) {
            log.warn("index_basic 表为空，回退到 INDEX_WEIGHT_CODES");
            indexCodes = IndexConstants.INDEX_WEIGHT_CODES;
        }
        for (String indexCode : indexCodes) {
            try {
                int n = indexWeightService.fetchAndSave(indexCode, tradeDate);
                log.info("IndexWeightFetchService synced: {} @ {} ({} records)", indexCode, tradeDate, n);
            } catch (Exception e) {
                log.error("IndexWeightFetchService 同步失败 {}: {}", indexCode, e.getMessage(), e);
            }
        }
        log.info("===== IndexWeightFetchService done =====");
    }
}
