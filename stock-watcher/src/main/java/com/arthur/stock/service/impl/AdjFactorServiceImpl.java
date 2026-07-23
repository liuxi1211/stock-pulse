package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.AdjFactorDTO;
import com.arthur.stock.dto.tushare.AdjFactorQueryDTO;
import com.arthur.stock.mapper.AdjFactorMapper;
import com.arthur.stock.model.AdjFactorDO;
import com.arthur.stock.service.AdjFactorService;
import com.arthur.stock.service.DataCheckable;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 复权因子服务实现类，负责从Tushare获取复权因子数据并持久化到本地数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdjFactorServiceImpl implements AdjFactorService, DataCheckable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final AdjFactorMapper adjFactorMapper;

    @Override
    public List<AdjFactorDTO> queryByCodeAndDateRange(String tsCode, String startDate, String endDate) {
        AdjFactorQueryDTO param = AdjFactorQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        return tushareClient.adjFactor(param);
    }

    @Override
    public List<AdjFactorDTO> queryByTradeDate(String tradeDate) {
        AdjFactorQueryDTO param = AdjFactorQueryDTO.builder()
                .tradeDate(tradeDate)
                .build();
        return tushareClient.adjFactor(param);
    }

    @Override
    public List<AdjFactorDTO> fetchAndSaveAdjFactor(String tsCode) {
        String lastDate = getLastTradeDate(tsCode);
        return doFetchAndSaveAdjFactor(tsCode, lastDate);
    }

    @Override
    public List<AdjFactorDTO> fetchAndSaveAdjFactor(String tsCode, String knownLastDate) {
        return doFetchAndSaveAdjFactor(tsCode, knownLastDate);
    }

    private List<AdjFactorDTO> doFetchAndSaveAdjFactor(String tsCode, String lastDate) {
        String startDate;
        if (lastDate != null) {
            LocalDate ld = LocalDate.parse(lastDate, DATE_FMT);
            startDate = ld.plusDays(1).format(DATE_FMT);
        } else {
            startDate = LocalDate.now().minusYears(30).format(DATE_FMT);
        }

        String endDate = LocalDate.now().format(DATE_FMT);

        if (startDate.compareTo(endDate) > 0) {
            log.info("Stock {} adj_factor is up to date", tsCode);
            return Collections.emptyList();
        }

        log.info("Fetching adj_factor for {} from {} to {}", tsCode, startDate, endDate);

        AdjFactorQueryDTO param = AdjFactorQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<AdjFactorDTO> factors = tushareClient.adjFactor(param);

        if (factors.isEmpty()) {
            log.info("No adj_factor data returned for {}", tsCode);
            return Collections.emptyList();
        }

        List<AdjFactorDO> entities = factors.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveAdjFactors(entities);
        log.info("Saved {} adj_factor records for {}", entities.size(), tsCode);
        return factors;
    }

    @Override
    public List<AdjFactorDTO> fetchAndSaveByTradeDate(String tradeDate) {
        log.info("Fetching adj_factor for trade_date={}", tradeDate);

        AdjFactorQueryDTO param = AdjFactorQueryDTO.builder()
                .tradeDate(tradeDate)
                .build();
        List<AdjFactorDTO> factors = tushareClient.adjFactor(param);

        if (factors.isEmpty()) {
            log.info("No adj_factor data returned for trade_date={}", tradeDate);
            return Collections.emptyList();
        }

        List<AdjFactorDO> entities = factors.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveAdjFactors(entities);
        log.info("Saved {} adj_factor records for trade_date={}", entities.size(), tradeDate);
        return factors;
    }

    private String getLastTradeDate(String tsCode) {
        AdjFactorDO last = adjFactorMapper.selectOne(
                new LambdaQueryWrapper<AdjFactorDO>()
                        .eq(AdjFactorDO::getTsCode, tsCode)
                        .orderByDesc(AdjFactorDO::getTradeDate)
                        .last("LIMIT 1"));
        return last != null ? last.getTradeDate() : null;
    }

    /**
     * 从本地数据库查询指定股票的全部复权因子（按日期升序）
     */
    @Override
    public List<AdjFactorDO> queryLocalByTsCode(String tsCode) {
        return adjFactorMapper.selectList(
                new LambdaQueryWrapper<AdjFactorDO>()
                        .eq(AdjFactorDO::getTsCode, tsCode)
                        .orderByAsc(AdjFactorDO::getTradeDate));
    }

    private AdjFactorDO toEntity(AdjFactorDTO dto) {
        return AdjFactorDO.builder()
                .tsCode(dto.getTsCode())
                .tradeDate(dto.getTradeDate())
                .adjFactor(dto.getAdjFactor())
                .build();
    }

    /**
     * 批量保存复权因子数据。先删除同主键（ts_code+trade_date）已存在记录，再插入；跨方言通用。
     */
    private void saveAdjFactors(List<AdjFactorDO> factors) {
        Lists.partition(factors, BATCH_SIZE).forEach(batch -> {
            adjFactorMapper.deleteBatchByKeys(batch);
            adjFactorMapper.insertBatch(batch);
        });
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.ADJ_FACTOR.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = adjFactorMapper.selectCount(null);
            String latestDate = adjFactorMapper.selectLatestTradeDate();
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
            boolean factorPassed;
            String factorMsg;
            if (totalRows == 0) {
                factorPassed = true;
                factorMsg = "表为空，跳过检测";
            } else {
                int invalidCount = adjFactorMapper.countInvalidFactor(thirtyDaysAgo);
                factorPassed = invalidCount == 0;
                factorMsg = factorPassed ? "通过，最近 30 天复权因子正常" : "最近 30 天异常复权因子 " + invalidCount + " 条";
            }
            items.add(DataCheckItem.builder()
                    .name("factor_validity")
                    .displayName("复权因子有效性检测")
                    .passed(factorPassed)
                    .level(CheckLevel.ERROR)
                    .message(factorMsg)
                    .build());

            String sevenDaysAgo = today.minusDays(7).format(DATE_FMT);
            boolean coveragePassed;
            String coverageMsg;
            if (totalRows == 0) {
                coveragePassed = true;
                coverageMsg = "表为空，跳过检测";
            } else {
                int missingCount = adjFactorMapper.countMissingInAdjFactor(sevenDaysAgo);
                coveragePassed = missingCount == 0;
                coverageMsg = coveragePassed ? "通过，最近 7 天行情覆盖完整" : "最近 7 天缺失复权因子的股票 " + missingCount + " 只";
            }
            items.add(DataCheckItem.builder()
                    .name("quote_coverage")
                    .displayName("行情覆盖一致性检测")
                    .passed(coveragePassed)
                    .level(CheckLevel.WARN)
                    .message(coverageMsg)
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.ADJ_FACTOR.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for adj_factor", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.ADJ_FACTOR.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}