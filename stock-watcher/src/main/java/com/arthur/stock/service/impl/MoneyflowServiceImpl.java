package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.MoneyflowDTO;
import com.arthur.stock.mapper.MoneyflowMapper;
import com.arthur.stock.model.MoneyflowDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.MoneyflowService;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 个股资金流向数据服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoneyflowServiceImpl implements MoneyflowService, DataCheckable {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MoneyflowMapper moneyflowMapper;
    private final TushareClient tushareClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSave(String tradeDate) {
        log.info("拉取 moneyflow tradeDate={}", tradeDate);
        List<MoneyflowDTO> rows = tushareClient.moneyflow(tradeDate, null);
        if (rows == null || rows.isEmpty()) {
            log.info("moneyflow {} 无数据", tradeDate);
            return 0;
        }
        List<MoneyflowDO> entities = rows.stream().map(this::toEntity).filter(Objects::nonNull).collect(Collectors.toList());
        saveBatch(entities);
        log.info("moneyflow {} 保存 {} 条", tradeDate, entities.size());
        return entities.size();
    }

    @Override
    public List<MoneyflowDO> queryTop(String tradeDate, int limit, String sortBy, String order) {
        String effectiveDate = tradeDate;
        if (effectiveDate == null || effectiveDate.isBlank()) {
            effectiveDate = getLatestTradeDate();
            if (effectiveDate == null) {
                return Collections.emptyList();
            }
        }
        String safeSortBy = "net_mf_vol".equals(sortBy) ? "net_mf_vol" : "net_mf_amount";
        String safeOrder = "asc".equalsIgnoreCase(order) ? "asc" : "desc";
        return moneyflowMapper.selectTopByTradeDate(effectiveDate, limit, safeSortBy, safeOrder);
    }

    @Override
    public List<MoneyflowDO> queryDetail(String tsCode, int days) {
        if (tsCode == null || tsCode.isBlank()) {
            return Collections.emptyList();
        }
        String endDate = LocalDate.now().format(DATE_FMT);
        String startDate = LocalDate.now().minusDays(days).format(DATE_FMT);
        return moneyflowMapper.selectByCodeAndDateRange(tsCode, startDate, endDate);
    }

    @Override
    public String getLatestTradeDate() {
        return moneyflowMapper.selectLatestTradeDate();
    }

    // ==================== 内部 ====================

    private void saveBatch(List<MoneyflowDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            moneyflowMapper.deleteBatchByKeys(batch);
            moneyflowMapper.insertBatch(batch);
        });
    }

    private MoneyflowDO toEntity(MoneyflowDTO d) {
        if (d == null || d.getTsCode() == null || d.getTradeDate() == null) {
            return null;
        }
        return MoneyflowDO.builder()
                .tsCode(d.getTsCode())
                .tradeDate(d.getTradeDate())
                .buySmAmount(d.getBuySmAmount())
                .sellSmAmount(d.getSellSmAmount())
                .buySmVol(d.getBuySmVol())
                .sellSmVol(d.getSellSmVol())
                .buyMdAmount(d.getBuyMdAmount())
                .sellMdAmount(d.getSellMdAmount())
                .buyMdVol(d.getBuyMdVol())
                .sellMdVol(d.getSellMdVol())
                .buyLgAmount(d.getBuyLgAmount())
                .sellLgAmount(d.getSellLgAmount())
                .buyLgVol(d.getBuyLgVol())
                .sellLgVol(d.getSellLgVol())
                .buyElgAmount(d.getBuyElgAmount())
                .sellElgAmount(d.getSellElgAmount())
                .buyElgVol(d.getBuyElgVol())
                .sellElgVol(d.getSellElgVol())
                .netMfAmount(d.getNetMfAmount())
                .netMfVol(d.getNetMfVol())
                .build();
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.MONEYFLOW.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = moneyflowMapper.selectCount(null);
            String latestDate = moneyflowMapper.selectLatestTradeDate();
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
            boolean amountPassed;
            String amountMsg;
            if (totalRows == 0) {
                amountPassed = true;
                amountMsg = "表为空，跳过检测";
            } else {
                int invalidCount = moneyflowMapper.countInvalidAmount(thirtyDaysAgo);
                amountPassed = invalidCount == 0;
                amountMsg = amountPassed ? "通过，最近 30 天资金流向金额正常" : "最近 30 天异常资金流向金额 " + invalidCount + " 条";
            }
            items.add(DataCheckItem.builder()
                    .name("amount_validity")
                    .displayName("资金流向金额有效性检测")
                    .passed(amountPassed)
                    .level(CheckLevel.ERROR)
                    .message(amountMsg)
                    .build());

            boolean consistencyPassed;
            String consistencyMsg;
            if (totalRows == 0) {
                consistencyPassed = true;
                consistencyMsg = "表为空，跳过检测";
            } else {
                int invalidCount = moneyflowMapper.countNetAmountInconsistency(thirtyDaysAgo);
                consistencyPassed = invalidCount == 0;
                consistencyMsg = consistencyPassed ? "通过，最近 30 天净额一致性正常" : "最近 30 天净额不一致 " + invalidCount + " 条";
            }
            items.add(DataCheckItem.builder()
                    .name("net_amount_consistency")
                    .displayName("净额一致性检测")
                    .passed(consistencyPassed)
                    .level(CheckLevel.WARN)
                    .message(consistencyMsg)
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.MONEYFLOW.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for moneyflow", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.MONEYFLOW.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
