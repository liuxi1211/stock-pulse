package com.arthur.stock.service.impl;

import com.arthur.stock.mapper.DailyBasicMapper;
import com.arthur.stock.model.DailyBasicDO;
import com.arthur.stock.service.DailyBasicService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 每日基本面数据服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyBasicServiceImpl implements DailyBasicService {

    private final DailyBasicMapper dailyBasicMapper;

    @Override
    public DailyBasicDO getByCodeAndDate(String tsCode, String tradeDate) {
        if (tsCode == null || tsCode.isBlank()) {
            return null;
        }
        String effectiveDate = tradeDate;
        if (effectiveDate == null || effectiveDate.isBlank()) {
            effectiveDate = dailyBasicMapper.selectLatestTradeDate();
            if (effectiveDate == null) {
                return null;
            }
        }
        return dailyBasicMapper.selectByCodeAndDate(tsCode, effectiveDate);
    }

    @Override
    public List<DailyBasicDO> listByCodeAndDateRange(String tsCode, String startDate, String endDate) {
        if (tsCode == null || tsCode.isBlank()) {
            return Collections.emptyList();
        }
        return dailyBasicMapper.selectByCodeAndDateRange(tsCode, startDate, endDate);
    }

    @Override
    public List<DailyBasicDO> listByCodesAndDate(List<String> tsCodes, String tradeDate) {
        if (tsCodes == null || tsCodes.isEmpty()) {
            return Collections.emptyList();
        }
        String effectiveDate = tradeDate;
        if (effectiveDate == null || effectiveDate.isBlank()) {
            effectiveDate = dailyBasicMapper.selectLatestTradeDate();
            if (effectiveDate == null) {
                return Collections.emptyList();
            }
        }
        return dailyBasicMapper.selectByCodesAndDate(tsCodes, effectiveDate);
    }

    @Override
    public String getLatestTradeDate() {
        return dailyBasicMapper.selectLatestTradeDate();
    }
}
