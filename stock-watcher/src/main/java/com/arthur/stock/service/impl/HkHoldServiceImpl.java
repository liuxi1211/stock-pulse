package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.HkHoldTrendVO;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.HkHoldDTO;
import com.arthur.stock.mapper.HkHoldMapper;
import com.arthur.stock.model.HkHoldDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.HkHoldService;
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
 * 沪深港通持股明细服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HkHoldServiceImpl implements HkHoldService, DataCheckable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final HkHoldMapper hkHoldMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSave(String tradeDate) {
        log.info("Fetching hk_hold for trade_date={}", tradeDate);

        List<HkHoldDTO> dtos = tushareClient.hkHold(tradeDate, null);
        if (dtos.isEmpty()) {
            log.info("No hk_hold data returned for trade_date={}", tradeDate);
            return 0;
        }

        List<HkHoldDO> entities = dtos.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveBatch(entities);
        log.info("Saved {} hk_hold records for trade_date={}", entities.size(), tradeDate);
        return entities.size();
    }

    @Override
    public List<HkHoldTrendVO> queryRatioTrend(int days, String exchangeId) {
        String endDate = LocalDate.now().format(DATE_FMT);
        String startDate = LocalDate.now().minusDays(days).format(DATE_FMT);
        String effectiveExchangeId = normalizeExchangeId(exchangeId);
        return hkHoldMapper.selectRatioTrend(startDate, endDate, effectiveExchangeId);
    }

    @Override
    public List<HkHoldDO> queryTopHoldings(String tradeDate, String exchangeId, int limit) {
        String effectiveDate = tradeDate;
        if (effectiveDate == null || effectiveDate.isBlank()) {
            effectiveDate = hkHoldMapper.selectLatestTradeDate();
            if (effectiveDate == null) {
                return Collections.emptyList();
            }
        }
        String effectiveExchangeId = normalizeExchangeId(exchangeId);
        return hkHoldMapper.selectTopHoldings(effectiveDate, effectiveExchangeId, limit);
    }

    @Override
    public List<HkHoldDO> queryDetail(String tsCode, int days) {
        if (tsCode == null || tsCode.isBlank()) {
            return Collections.emptyList();
        }
        String endDate = LocalDate.now().format(DATE_FMT);
        String startDate = LocalDate.now().minusDays(days).format(DATE_FMT);
        return hkHoldMapper.selectByCodeAndDateRange(tsCode, startDate, endDate);
    }

    @Override
    public String getLatestTradeDate() {
        return hkHoldMapper.selectLatestTradeDate();
    }

    /**
     * 规范化 exchangeId：null/空/"ALL" 统一转为 null（表示不过滤）。
     */
    private String normalizeExchangeId(String exchangeId) {
        if (exchangeId == null || exchangeId.isBlank() || "ALL".equalsIgnoreCase(exchangeId)) {
            return null;
        }
        return exchangeId;
    }

    private HkHoldDO toEntity(HkHoldDTO dto) {
        if (dto == null || dto.getTradeDate() == null || dto.getCode() == null) {
            return null;
        }
        return HkHoldDO.builder()
                .tradeDate(dto.getTradeDate())
                .code(dto.getCode())
                .name(dto.getName())
                .vol(dto.getVol())
                .ratio(dto.getRatio())
                .tsCode(dto.getTsCode())
                .exchangeId(dto.getExchangeId())
                .build();
    }

    /**
     * 批量保存：先删除同主键（trade_date + code）已存在记录，再插入；跨方言通用。
     */
    private void saveBatch(List<HkHoldDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            hkHoldMapper.deleteBatchByKeys(batch);
            hkHoldMapper.insertBatch(batch);
        });
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.HK_HOLD.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = hkHoldMapper.selectCount(null);
            String latestDate = hkHoldMapper.selectLatestTradeDate();
            LocalDate today = LocalDate.now();
            String todayStr = today.format(DATE_FMT);

            // Check 1: Freshness (ERROR) - max(trade_date) < 上一交易日 - 1天（T+1，多容忍1天）
            boolean freshnessPassed;
            String freshnessMsg;
            if (totalRows == 0 || latestDate == null) {
                freshnessPassed = true;
                freshnessMsg = "表为空，跳过检测";
            } else {
                // 上一交易日 - 1天 = 今天 - 2天（简化处理，不考虑节假日，按自然日）
                String twoDaysAgo = today.minusDays(2).format(DATE_FMT);
                freshnessPassed = latestDate.compareTo(twoDaysAgo) >= 0;
                freshnessMsg = freshnessPassed ? "通过，最新数据 " + latestDate
                        : "最新交易日为 " + latestDate + "，疑似延迟（T+1+1容忍）";
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
                        .name("vol_validity")
                        .displayName("持股数量有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("ratio_validity")
                        .displayName("持股占比有效性检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
            } else {
                String thirtyDaysAgo = today.minusDays(30).format(DATE_FMT);

                // Check 2: vol 有效性（ERROR）
                int invalidVol = hkHoldMapper.countInvalidVol(thirtyDaysAgo);
                boolean volPassed = invalidVol == 0;
                items.add(DataCheckItem.builder()
                        .name("vol_validity")
                        .displayName("持股数量有效性检测")
                        .passed(volPassed)
                        .level(CheckLevel.ERROR)
                        .message(volPassed ? "通过，最近 30 天无异常"
                                : "最近 30 天 vol < 0 记录 " + invalidVol + " 条")
                        .build());

                // Check 3: ratio 有效性（WARN）
                int invalidRatio = hkHoldMapper.countInvalidRatio(thirtyDaysAgo);
                boolean ratioPassed = invalidRatio == 0;
                items.add(DataCheckItem.builder()
                        .name("ratio_validity")
                        .displayName("持股占比有效性检测")
                        .passed(ratioPassed)
                        .level(CheckLevel.WARN)
                        .message(ratioPassed ? "通过，最近 30 天无异常"
                                : "最近 30 天 ratio 异常记录 " + invalidRatio + " 条")
                        .build());
            }

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.HK_HOLD.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for hk_hold", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.HK_HOLD.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
