package com.arthur.stock.service.impl;

import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.mapper.IndexDailyMapper;
import com.arthur.stock.model.IndexDailyDO;
import com.arthur.stock.service.IndexDailyService;
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
 * 指数日线行情查询服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexDailyServiceImpl implements IndexDailyService, DataCheckable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

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

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.INDEX_DAILY.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = indexDailyMapper.selectCount(null);
            String latestDate = indexDailyMapper.selectLatestTradeDate();
            LocalDate today = LocalDate.now();
            String todayStr = today.format(DATE_FMT);

            // Check 1: Freshness (ERROR)
            boolean isWeekday = today.getDayOfWeek().getValue() <= 5;
            boolean freshnessPassed = !isWeekday || (latestDate != null && latestDate.compareTo(todayStr) >= 0);
            items.add(DataCheckItem.builder()
                    .name("freshness")
                    .displayName("新鲜度检测")
                    .passed(freshnessPassed)
                    .level(CheckLevel.ERROR)
                    .message(freshnessPassed ? "通过，最新数据 " + latestDate : "最新交易日为 " + latestDate + "，疑似延迟")
                    .build());

            // Check 2: Missing core indices (ERROR)
            String thirtyDaysAgo = today.minusDays(30).format(DATE_FMT);
            boolean indicesPassed;
            String indicesMsg;
            if (totalRows == 0) {
                indicesPassed = true;
                indicesMsg = "表为空，跳过检测";
            } else {
                List<String> missing = indexDailyMapper.selectMissingCoreIndices(thirtyDaysAgo);
                indicesPassed = missing == null || missing.isEmpty();
                indicesMsg = indicesPassed ? "通过，核心指数数据完整"
                        : "缺少核心指数：" + String.join(", ", missing);
            }
            items.add(DataCheckItem.builder()
                    .name("core_indices")
                    .displayName("核心指数完整性检测")
                    .passed(indicesPassed)
                    .level(CheckLevel.ERROR)
                    .message(indicesMsg)
                    .build());

            // Check 3: Price anomalies (ERROR)
            boolean pricePassed;
            String priceMsg;
            if (totalRows == 0) {
                pricePassed = true;
                priceMsg = "表为空，跳过检测";
            } else {
                int anomalies = indexDailyMapper.countPriceAnomalies(thirtyDaysAgo);
                pricePassed = anomalies == 0;
                priceMsg = pricePassed ? "通过，最近 30 天无异常" : "最近 30 天价格异常记录 " + anomalies + " 条";
            }
            items.add(DataCheckItem.builder()
                    .name("price_logic")
                    .displayName("价格逻辑检测")
                    .passed(pricePassed)
                    .level(CheckLevel.ERROR)
                    .message(priceMsg)
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.INDEX_DAILY.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for index_daily", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.INDEX_DAILY.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
