package com.arthur.stock.service.impl;

import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.mapper.DailyBasicMapper;
import com.arthur.stock.model.DailyBasicDO;
import com.arthur.stock.service.DailyBasicService;
import com.arthur.stock.service.DataCheckable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 每日基本面数据服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyBasicServiceImpl implements DailyBasicService, DataCheckable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

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

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.DAILY_BASIC.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = dailyBasicMapper.selectCount(null);
            String latestDate = dailyBasicMapper.selectLatestTradeDate();
            LocalDate today = LocalDate.now();
            String todayStr = today.format(DATE_FMT);

            boolean isWeekday = today.getDayOfWeek().getValue() <= 5;
            boolean freshnessPassed = !isWeekday || (latestDate != null && latestDate.compareTo(todayStr) >= 0);
            items.add(DataCheckItem.builder()
                    .name("freshness")
                    .displayName("新鲜度检测")
                    .passed(freshnessPassed)
                    .level(CheckLevel.ERROR)
                    .message(freshnessPassed ? "通过，最新数据 " + latestDate : "最新交易日为 " + latestDate + "，疑似延迟")
                    .build());

            String thirtyDaysAgo = today.minusDays(30).format(DATE_FMT);
            boolean mvPassed;
            String mvMsg;
            if (totalRows == 0) {
                mvPassed = true;
                mvMsg = "表为空，跳过检测";
            } else {
                int invalidCount = dailyBasicMapper.countInvalidMv(thirtyDaysAgo);
                mvPassed = invalidCount == 0;
                mvMsg = mvPassed ? "通过，最近 30 天总市值正常" : "最近 30 天异常总市值 " + invalidCount + " 条";
            }
            items.add(DataCheckItem.builder()
                    .name("mv_validity")
                    .displayName("总市值有效性检测")
                    .passed(mvPassed)
                    .level(CheckLevel.ERROR)
                    .message(mvMsg)
                    .build());

            boolean turnoverPassed;
            String turnoverMsg;
            if (totalRows == 0) {
                turnoverPassed = true;
                turnoverMsg = "表为空，跳过检测";
            } else {
                int invalidCount = dailyBasicMapper.countInvalidTurnover(thirtyDaysAgo);
                turnoverPassed = invalidCount == 0;
                turnoverMsg = turnoverPassed ? "通过，最近 30 天换手率/量比正常" : "最近 30 天异常换手率/量比 " + invalidCount + " 条";
            }
            items.add(DataCheckItem.builder()
                    .name("turnover_validity")
                    .displayName("换手率/量比有效性检测")
                    .passed(turnoverPassed)
                    .level(CheckLevel.WARN)
                    .message(turnoverMsg)
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.DAILY_BASIC.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for daily_basic", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.DAILY_BASIC.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
