package com.arthur.stock.service.impl;

import com.arthur.stock.mapper.IndexDailyMapper;
import com.arthur.stock.model.IndexDailyDO;
import com.arthur.stock.service.IndexDailyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 指数日线行情查询服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexDailyServiceImpl implements IndexDailyService {

    private final IndexDailyMapper indexDailyMapper;

    @Override
    public List<IndexDailyDO> getLatestByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Collections.emptyList();
        }
        String latest = indexDailyMapper.selectLatestTradeDate();
        if (latest == null || latest.isEmpty()) {
            log.warn("getLatestByCodes: index_daily 表为空，无最新交易日");
            return Collections.emptyList();
        }
        return indexDailyMapper.selectByCodesAndTradeDate(codes, latest);
    }

    @Override
    public List<IndexDailyDO> getByCodesAndTradeDate(List<String> codes, String tradeDate) {
        if (codes == null || codes.isEmpty() || tradeDate == null || tradeDate.isEmpty()) {
            return Collections.emptyList();
        }
        return indexDailyMapper.selectByCodesAndTradeDate(codes, tradeDate);
    }

    @Override
    public List<IndexDailyDO> getByCodeOrderByTradeDate(String tsCode, int limit) {
        if (tsCode == null || tsCode.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        return indexDailyMapper.selectByCodeOrderByTradeDate(tsCode, limit);
    }
}
