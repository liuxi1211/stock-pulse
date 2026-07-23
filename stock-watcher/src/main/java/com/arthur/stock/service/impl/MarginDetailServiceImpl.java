package com.arthur.stock.service.impl;

import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.mapper.MarginDetailMapper;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.MarginDetailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 融资融券明细数据服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarginDetailServiceImpl implements MarginDetailService, DataCheckable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MarginDetailMapper marginDetailMapper;

    @Override
    public String getLatestTradeDate() {
        return marginDetailMapper.selectLatestTradeDate();
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.MARGIN_DETAIL.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = marginDetailMapper.selectCount(null);
            String latestDate = marginDetailMapper.selectLatestTradeDate();
            LocalDate today = LocalDate.now();

            // Check 1: Freshness (ERROR) - max(trade_date) < 上一交易日
            boolean freshnessPassed;
            String freshnessMsg;
            if (totalRows == 0 || latestDate == null) {
                freshnessPassed = true;
                freshnessMsg = "表为空，跳过检测";
            } else {
                String yesterday = today.minusDays(1).format(DATE_FMT);
                freshnessPassed = latestDate.compareTo(yesterday) >= 0;
                freshnessMsg = freshnessPassed ? "通过，最新数据 " + latestDate
                        : "最新交易日为 " + latestDate + "，疑似延迟";
            }
            items.add(DataCheckItem.builder()
                    .name("freshness")
                    .displayName("新鲜度检测")
                    .passed(freshnessPassed)
                    .level(CheckLevel.ERROR)
                    .message(freshnessMsg)
                    .build());

            if (totalRows == 0) {
                // 空表，剩余检测项跳过
                items.add(DataCheckItem.builder()
                        .name("balance_validity")
                        .displayName("余额有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
            } else {
                String thirtyDaysAgo = today.minusDays(30).format(DATE_FMT);

                // Check 2: 余额有效性（ERROR）
                int invalidBalance = marginDetailMapper.countInvalidBalance(thirtyDaysAgo);
                boolean balancePassed = invalidBalance == 0;
                items.add(DataCheckItem.builder()
                        .name("balance_validity")
                        .displayName("余额有效性检测")
                        .passed(balancePassed)
                        .level(CheckLevel.ERROR)
                        .message(balancePassed ? "通过，最近 30 天无异常"
                                : "最近 30 天余额异常记录 " + invalidBalance + " 条")
                        .build());
            }

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.MARGIN_DETAIL.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for margin_detail", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.MARGIN_DETAIL.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
