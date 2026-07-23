package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.IndexWeightDTO;
import com.arthur.stock.dto.tushare.IndexWeightQueryDTO;
import com.arthur.stock.mapper.IndexWeightMapper;
import com.arthur.stock.model.IndexWeightDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.IndexWeightService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 指数成分股权重服务实现。
 * <p>
 * 数据源：tushare index_weight 接口（需 2000 积分，当前 token 满足）。
 * 落库策略：按 (ts_code, trade_date) 维度先删后插，实现幂等 upsert。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexWeightServiceImpl implements IndexWeightService, DataCheckable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final IndexWeightMapper indexWeightMapper;

    @Override
    public int fetchAndSave(String indexCode, String tradeDate) {
        log.info("Fetching index_weight: indexCode={}, tradeDate={}", indexCode, tradeDate);

        IndexWeightQueryDTO param = IndexWeightQueryDTO.builder()
                .indexCode(indexCode)
                .tradeDate(tradeDate)
                .build();
        List<IndexWeightDTO> rows = tushareClient.indexWeight(param);

        if (rows.isEmpty()) {
            log.info("No index_weight data for indexCode={}, tradeDate={}", indexCode, tradeDate);
            return 0;
        }

        List<IndexWeightDO> entities = rows.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        int saved = saveBatch(indexCode, tradeDate, entities);
        log.info("Saved {} index_weight records for indexCode={}, tradeDate={}",
                saved, indexCode, tradeDate);
        return saved;
    }

    @Override
    public int fetchAndSaveRange(String indexCode, String startDate, String endDate) {
        log.info("Fetching index_weight range: indexCode={}, {}~{}", indexCode, startDate, endDate);

        IndexWeightQueryDTO param = IndexWeightQueryDTO.builder()
                .indexCode(indexCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<IndexWeightDTO> rows = tushareClient.indexWeight(param);

        if (rows.isEmpty()) {
            log.info("No index_weight data for indexCode={}, {}~{}", indexCode, startDate, endDate);
            return 0;
        }

        List<IndexWeightDO> entities = rows.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        int total = 0;
        List<String> dates = entities.stream()
                .map(IndexWeightDO::getTradeDate)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        for (String date : dates) {
            List<IndexWeightDO> dayRows = entities.stream()
                    .filter(e -> date.equals(e.getTradeDate()))
                    .collect(Collectors.toList());
            total += saveBatch(indexCode, date, dayRows);
        }
        log.info("Saved {} index_weight records for indexCode={}, {}~{} ({} trade dates)",
                total, indexCode, startDate, endDate, dates.size());
        return total;
    }

    @Override
    public List<String> getLatestConstituents(String indexCode) {
        List<String> codes = indexWeightMapper.selectLatestConstituents(indexCode);
        return codes == null ? Collections.emptyList() : codes;
    }

    @Override
    public List<String> getConstituentsAt(String indexCode, String tradeDate) {
        List<String> codes = indexWeightMapper.selectConstituentsAt(indexCode, tradeDate);
        return codes == null ? Collections.emptyList() : codes;
    }

    @Override
    public String getEffectiveDate(String indexCode, String tradeDate) {
        return indexWeightMapper.selectEffectiveDate(indexCode, tradeDate);
    }

    @Override
    public List<String> getConstituentsInRange(String indexCode, String startDate, String endDate) {
        List<String> codes = indexWeightMapper.selectConstituentsInRange(indexCode, startDate, endDate);
        return codes == null ? Collections.emptyList() : codes;
    }

    private IndexWeightDO toEntity(IndexWeightDTO dto) {
        if (dto == null || dto.getTsCode() == null || dto.getTradeDate() == null || dto.getConCode() == null) {
            return null;
        }
        return IndexWeightDO.builder()
                .tsCode(dto.getTsCode())
                .tradeDate(dto.getTradeDate())
                .conCode(dto.getConCode())
                .weight(dto.getWeight())
                .build();
    }

    /**
     * 按 (ts_code, trade_date) 先删后插，实现幂等。
     */
    private int saveBatch(String indexCode, String tradeDate, List<IndexWeightDO> rows) {
        indexWeightMapper.delete(new LambdaQueryWrapper<IndexWeightDO>()
                .eq(IndexWeightDO::getTsCode, indexCode)
                .eq(IndexWeightDO::getTradeDate, tradeDate));

        if (rows.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (List<IndexWeightDO> batch : Lists.partition(rows, BATCH_SIZE)) {
            count += indexWeightMapper.insertBatch(batch);
        }
        return count;
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.INDEX_WEIGHT.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = indexWeightMapper.selectCount(null);
            String latestDate = indexWeightMapper.selectLatestTradeDate();

            // Check 1: Freshness (ERROR) - 指数权重按月更新，max(trade_date) 所在月份 < 上月
            boolean freshnessPassed;
            String freshnessMsg;
            if (totalRows == 0 || latestDate == null) {
                freshnessPassed = true;
                freshnessMsg = "表为空，跳过检测";
            } else {
                YearMonth latestYm = YearMonth.parse(latestDate.substring(0, 6), DateTimeFormatter.ofPattern("yyyyMM"));
                YearMonth lastMonth = YearMonth.now().minusMonths(1);
                freshnessPassed = !latestYm.isBefore(lastMonth);
                freshnessMsg = freshnessPassed ? "通过，最新数据 " + latestDate
                        : "最新数据月份 " + latestYm + "，早于上月 " + lastMonth;
            }
            items.add(DataCheckItem.builder()
                    .name("freshness")
                    .displayName("新鲜度检测")
                    .passed(freshnessPassed)
                    .level(CheckLevel.ERROR)
                    .message(freshnessMsg)
                    .build());

            if (totalRows == 0 || latestDate == null) {
                // 空表，剩余检测项跳过
                items.add(DataCheckItem.builder()
                        .name("component_count")
                        .displayName("核心指数成分股数量检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("weight_sum")
                        .displayName("权重总和检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("weight_validity")
                        .displayName("权重有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
            } else {
                // Check 2: 核心指数成分股数量（ERROR）
                List<Map<String, Object>> counts = indexWeightMapper.countByIndexCode(latestDate);
                Map<String, Integer> countMap = counts.stream()
                        .collect(Collectors.toMap(
                                m -> (String) m.get("tsCode"),
                                m -> ((Number) m.get("cnt")).intValue()
                        ));

                StringBuilder sb = new StringBuilder();
                boolean countPassed = true;

                // 沪深300: 280-320
                Integer hs300 = countMap.get("000300.SH");
                if (hs300 == null || hs300 < 280 || hs300 > 320) {
                    countPassed = false;
                    sb.append("沪深300=").append(hs300 == null ? "缺失" : hs300).append(" ");
                }

                // 中证500: 480-520
                Integer zz500 = countMap.get("000905.SH");
                if (zz500 == null || zz500 < 480 || zz500 > 520) {
                    countPassed = false;
                    sb.append("中证500=").append(zz500 == null ? "缺失" : zz500).append(" ");
                }

                // 中证1000: 950-1050
                Integer zz1000 = countMap.get("000852.SH");
                if (zz1000 == null || zz1000 < 950 || zz1000 > 1050) {
                    countPassed = false;
                    sb.append("中证1000=").append(zz1000 == null ? "缺失" : zz1000).append(" ");
                }

                items.add(DataCheckItem.builder()
                        .name("component_count")
                        .displayName("核心指数成分股数量检测")
                        .passed(countPassed)
                        .level(CheckLevel.ERROR)
                        .message(countPassed ? "通过，核心指数成分股数量正常" : "异常：" + sb.toString().trim())
                        .build());

                // Check 3: 权重总和（WARN）
                int abnormalSum = indexWeightMapper.countWeightSumAbnormal(latestDate);
                boolean sumPassed = abnormalSum == 0;
                items.add(DataCheckItem.builder()
                        .name("weight_sum")
                        .displayName("权重总和检测")
                        .passed(sumPassed)
                        .level(CheckLevel.WARN)
                        .message(sumPassed ? "通过，所有权重总和在 99-101 区间"
                                : "权重总和异常的指数数量：" + abnormalSum)
                        .build());

                // Check 4: 权重有效性（ERROR）
                int invalidWeight = indexWeightMapper.countInvalidWeight(latestDate);
                boolean weightValidPassed = invalidWeight == 0;
                items.add(DataCheckItem.builder()
                        .name("weight_validity")
                        .displayName("权重有效性检测")
                        .passed(weightValidPassed)
                        .level(CheckLevel.ERROR)
                        .message(weightValidPassed ? "通过，无无效权重记录"
                                : "无效权重记录数：" + invalidWeight)
                        .build());
            }

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.INDEX_WEIGHT.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for index_weight", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.INDEX_WEIGHT.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
