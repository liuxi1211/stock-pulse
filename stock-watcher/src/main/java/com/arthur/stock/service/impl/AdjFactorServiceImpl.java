package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.AdjFactorDTO;
import com.arthur.stock.dto.tushare.AdjFactorQueryDTO;
import com.arthur.stock.dto.tushare.TradeCalDTO;
import com.arthur.stock.mapper.AdjFactorMapper;
import com.arthur.stock.model.AdjFactorDO;
import com.arthur.stock.service.AdjFactorService;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.TradeCalService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    /** 每次按日期范围拉取的交易日数量，控制单次返回数据量避免分页截断。
     *  Tushare 单次最大返回 5000 行，全市场约 5000 只股票，
     *  10 个交易日约 5 万行，在 10 万行分页上限内安全。 */
    private static final int DATE_RANGE_CHUNK_SIZE = 10;

    private final TushareClient tushareClient;
    private final AdjFactorMapper adjFactorMapper;
    private final TradeCalService tradeCalService;

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
    @Transactional(rollbackFor = Exception.class)
    public List<AdjFactorDTO> fetchAndSaveAdjFactor(String tsCode) {
        String lastDate = getLastTradeDate(tsCode);
        return doFetchAndSaveAdjFactor(tsCode, lastDate);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AdjFactorDTO> fetchAndSaveAdjFactor(String tsCode, String knownLastDate) {
        return doFetchAndSaveAdjFactor(tsCode, knownLastDate);
    }

    private List<AdjFactorDTO> doFetchAndSaveAdjFactor(String tsCode, String lastDate) {
        String startDate;
        if (lastDate != null) {
            startDate = lastDate;
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
    @Transactional(rollbackFor = Exception.class)
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

    @Override
    public int fetchAndSaveByDateRange(String startDate, String endDate) {
        if (startDate == null || endDate == null || startDate.compareTo(endDate) > 0) {
            log.warn("Invalid date range for adj_factor: start={}, end={}", startDate, endDate);
            return 0;
        }

        // 获取日期范围内的所有交易日（用于按 10 天一个窗口分段）
        // 用 SSE（上交所）交易日历即可，沪深交易所交易日基本一致
        List<TradeCalDTO> tradeCals = tradeCalService.queryLocal(
                "SSE", startDate, endDate, "1");
        if (tradeCals == null || tradeCals.isEmpty()) {
            log.info("No trade dates between {} and {} for adj_factor", startDate, endDate);
            return 0;
        }

        // 按 trade_date 升序排列（确保分段正确）
        List<String> tradeDates = tradeCals.stream()
                .map(TradeCalDTO::getCalDate)
                .sorted()
                .toList();

        log.info("Fetching adj_factor by date range: {} ~ {}, total {} trade days, chunk size={}",
                startDate, endDate, tradeDates.size(), DATE_RANGE_CHUNK_SIZE);

        int totalSaved = 0;
        int chunkIndex = 0;
        for (int i = 0; i < tradeDates.size(); i += DATE_RANGE_CHUNK_SIZE) {
            int endIdx = Math.min(i + DATE_RANGE_CHUNK_SIZE, tradeDates.size());
            String chunkStart = tradeDates.get(i);
            String chunkEnd = tradeDates.get(endIdx - 1);
            chunkIndex++;

            log.info("[AdjFactor chunk {}/{}] fetching {} ~ {}",
                    chunkIndex,
                    (tradeDates.size() + DATE_RANGE_CHUNK_SIZE - 1) / DATE_RANGE_CHUNK_SIZE,
                    chunkStart, chunkEnd);

            AdjFactorQueryDTO param = AdjFactorQueryDTO.builder()
                    .startDate(chunkStart)
                    .endDate(chunkEnd)
                    .build();
            List<AdjFactorDTO> factors = tushareClient.adjFactor(param);

            if (factors.isEmpty()) {
                log.info("[AdjFactor chunk {}/{}] no data returned for {} ~ {}",
                        chunkIndex,
                        (tradeDates.size() + DATE_RANGE_CHUNK_SIZE - 1) / DATE_RANGE_CHUNK_SIZE,
                        chunkStart, chunkEnd);
                continue;
            }

            List<AdjFactorDO> entities = factors.stream()
                    .map(this::toEntity)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            saveAdjFactors(entities);
            totalSaved += entities.size();

            log.info("[AdjFactor chunk {}/{}] saved {} records for {} ~ {}",
                    chunkIndex,
                    (tradeDates.size() + DATE_RANGE_CHUNK_SIZE - 1) / DATE_RANGE_CHUNK_SIZE,
                    entities.size(), chunkStart, chunkEnd);
        }

        log.info("AdjFactor date range fetch completed: {} ~ {}, total saved {} records",
                startDate, endDate, totalSaved);
        return totalSaved;
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
            boolean nullValidPassed;
            String nullValidMsg;
            if (totalRows == 0) {
                nullValidPassed = true;
                nullValidMsg = "表为空，跳过检测";
            } else {
                int nullInvalidCount = adjFactorMapper.countNullInvalidRecords(thirtyDaysAgo);
                nullValidPassed = nullInvalidCount == 0;
                nullValidMsg = nullValidPassed ? "通过，最近 30 天无 NULL/无效值记录"
                        : "最近 30 天 NULL/无效值记录 " + nullInvalidCount + " 条";
            }
            items.add(DataCheckItem.builder()
                    .name("null_invalid_check")
                    .displayName("空值/无效值检测")
                    .passed(nullValidPassed)
                    .level(CheckLevel.ERROR)
                    .message(nullValidMsg)
                    .build());

            boolean dupPassed;
            String dupMsg;
            if (totalRows == 0) {
                dupPassed = true;
                dupMsg = "表为空，跳过检测";
            } else {
                int dupCount = adjFactorMapper.countDuplicateRecords(thirtyDaysAgo);
                dupPassed = dupCount == 0;
                dupMsg = dupPassed ? "通过，最近 30 天无重复记录"
                        : "最近 30 天重复主键 " + dupCount + " 组";
            }
            items.add(DataCheckItem.builder()
                    .name("duplicate_check")
                    .displayName("重复记录检测")
                    .passed(dupPassed)
                    .level(CheckLevel.ERROR)
                    .message(dupMsg)
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