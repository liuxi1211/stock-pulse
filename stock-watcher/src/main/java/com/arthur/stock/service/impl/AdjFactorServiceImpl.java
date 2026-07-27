package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.constant.ListStatusEnum;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.AdjFactorDTO;
import com.arthur.stock.dto.tushare.AdjFactorQueryDTO;
import com.arthur.stock.dto.tushare.TradeCalDTO;
import com.arthur.stock.mapper.AdjFactorMapper;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.model.AdjFactorDO;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.service.AdjFactorService;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.TradeCalService;
import com.arthur.stock.service.TradeCalendarService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    /** A 股市场起始日期（1990-12-19 上交所开市），全量拉取的起始时间 */
    private static final String FULL_START_DATE = "19901219";
    /** Tushare adj_factor 单次最大返回行数（分页大小） */
    private static final int PAGE_SIZE = 5000;
    /** 单查询条件下最大分页页数（安全上限，防止无限循环） */
    private static final int MAX_PAGES_PER_QUERY = 100;
    /** 完整性抽样检测的样本量 */
    private static final int COMPLETENESS_SAMPLE_SIZE = 50;
    /** 完整性抽样检测的通过阈值（每只股票的记录完整率 >= 该值视为通过） */
    private static final double COMPLETENESS_PASS_RATIO = 0.99;

    private final TushareClient tushareClient;
    private final AdjFactorMapper adjFactorMapper;
    private final StockBasicMapper stockBasicMapper;
    private final TradeCalService tradeCalService;
    private final TradeCalendarService tradeCalendarService;
    private final TransactionTemplate transactionTemplate;

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
    public int fetchAndSaveAdjFactor(String tsCode) {
        String lastDate = getLastTradeDate(tsCode);
        return doFetchAndSaveAdjFactor(tsCode, lastDate);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveAdjFactor(String tsCode, String knownLastDate) {
        return doFetchAndSaveAdjFactor(tsCode, knownLastDate);
    }

    private int doFetchAndSaveAdjFactor(String tsCode, String lastDate) {
        String startDate;
        if (lastDate != null) {
            startDate = lastDate;
        } else {
            startDate = FULL_START_DATE;
        }

        String endDate = LocalDate.now().format(DATE_FMT);

        if (startDate.compareTo(endDate) > 0) {
            log.info("Stock {} adj_factor is up to date", tsCode);
            return 0;
        }

        log.info("Fetching adj_factor for {} from {} to {}", tsCode, startDate, endDate);

        AdjFactorQueryDTO param = AdjFactorQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();

        int totalSaved = fetchAndSavePaginated(param, "Stock " + tsCode);
        log.info("Finished fetching adj_factor for {}, total saved {} records", tsCode, totalSaved);
        return totalSaved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveByTradeDate(String tradeDate) {
        log.info("Fetching adj_factor for trade_date={}", tradeDate);

        AdjFactorQueryDTO param = AdjFactorQueryDTO.builder()
                .tradeDate(tradeDate)
                .build();

        int totalSaved = fetchAndSavePaginated(param, "trade_date=" + tradeDate);
        log.info("Finished fetching adj_factor for trade_date={}, total saved {} records", tradeDate, totalSaved);

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
     * 分页拉取复权因子数据并保存（查一页存一页，内存中始终只有一页数据）。
     * <p>
     * adj_factor 接口数据按 trade_date 倒序返回，每页最大 5000 条。
     *
     * @param baseParam 基础查询参数（不含 offset/limit）
     * @param logPrefix 日志前缀，用于区分不同调用场景
     * @return 实际保存的记录总数
     */
    private int fetchAndSavePaginated(AdjFactorQueryDTO baseParam, String logPrefix) {
        int totalSaved = 0;
        int offset = 0;
        int pageNum = 0;

        while (true) {
            pageNum++;
            if (pageNum > MAX_PAGES_PER_QUERY) {
                log.warn("[{}] reached max pages ({}), stopping early. total saved={}",
                        logPrefix, MAX_PAGES_PER_QUERY, totalSaved);
                break;
            }

            AdjFactorQueryDTO pageParam = AdjFactorQueryDTO.builder()
                    .tsCode(baseParam.getTsCode())
                    .tradeDate(baseParam.getTradeDate())
                    .startDate(baseParam.getStartDate())
                    .endDate(baseParam.getEndDate())
                    .offset(offset)
                    .limit(PAGE_SIZE)
                    .build();

            List<AdjFactorDTO> pageData = tushareClient.adjFactor(pageParam);

            if (pageData == null || pageData.isEmpty()) {
                log.debug("[{}] page {}: no data, finished. total saved={}",
                        logPrefix, pageNum - 1, totalSaved);
                break;
            }

            // 转实体并立即保存（每页一个独立短事务，不跨 Tushare API 调用）
            List<AdjFactorDO> entities = pageData.stream()
                    .map(this::toEntity)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            transactionTemplate.execute(status -> {
                saveAdjFactors(entities);
                return null;
            });
            totalSaved += entities.size();

            log.debug("[{}] page {}: fetched {}, saved {}, cumulative={}",
                    logPrefix, pageNum, pageData.size(), entities.size(), totalSaved);

            // 返回数据不足一页，说明已到最后一页
            if (pageData.size() < PAGE_SIZE) {
                break;
            }

            offset += PAGE_SIZE;
        }

        return totalSaved;
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

            // Check 1: 新鲜度 —— 以交易日历中最近一个交易日为基准，而非简单工作日判断
            String lastTradeDate = tradeCalendarService.getLatestTradeDate();
            boolean freshnessPassed = lastTradeDate != null && latestDate != null
                    && latestDate.compareTo(lastTradeDate) >= 0;
            String freshnessMsg;
            if (freshnessPassed) {
                freshnessMsg = "通过，最新数据 " + latestDate;
            } else {
                freshnessMsg = "最新交易日为 " + latestDate + "，最近交易日应为 " + lastTradeDate + "，疑似延迟";
            }
            items.add(DataCheckItem.builder()
                    .name("freshness")
                    .displayName("新鲜度检测")
                    .passed(freshnessPassed)
                    .level(CheckLevel.ERROR)
                    .message(freshnessMsg)
                    .build());

            // Check 2: 空值/无效值检测 —— 成本极低的兜底检查
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

            // Check 3: 股票覆盖度 —— 在市股票中有复权因子数据的比例
            long listedCount = stockBasicMapper.selectCount(
                    new LambdaQueryWrapper<StockBasicDO>()
                            .eq(StockBasicDO::getListStatus, ListStatusEnum.LISTED));
            int adjStockCount = adjFactorMapper.countDistinctStocks();
            double coverage = listedCount > 0 ? (double) adjStockCount / listedCount : 0;
            int coveragePct = (int) (coverage * 100);
            boolean coveragePassed = coverage >= 0.95;
            String coverageMsg;
            if (totalRows == 0) {
                coveragePassed = true;
                coverageMsg = "表为空，跳过检测";
            } else {
                coverageMsg = coveragePassed
                        ? "通过，覆盖 " + adjStockCount + " / " + listedCount + " 只在市股票（" + coveragePct + "%）"
                        : "覆盖度仅 " + coveragePct + "%（" + adjStockCount + " / " + listedCount + " 只在市股票）";
            }
            items.add(DataCheckItem.builder()
                    .name("stock_coverage")
                    .displayName("股票覆盖度检测")
                    .passed(coveragePassed)
                    .level(CheckLevel.WARN)
                    .message(coverageMsg)
                    .build());

            // Check 4: 单只股票完整性抽样 —— 抽样验证实际记录数 vs 预期交易日数
            String completenessMsg;
            boolean completenessPassed;
            if (totalRows == 0 || latestDate == null) {
                completenessPassed = true;
                completenessMsg = "表为空，跳过检测";
            } else {
                CompletenessSampleResult result = checkCompletenessBySampling(latestDate);
                completenessPassed = result.passed;
                completenessMsg = result.message;
            }
            items.add(DataCheckItem.builder()
                    .name("per_stock_completeness")
                    .displayName("单只股票完整性抽样")
                    .passed(completenessPassed)
                    .level(CheckLevel.WARN)
                    .message(completenessMsg)
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

    /**
     * 单只股票完整性抽样检测结果。
     */
    private static class CompletenessSampleResult {
        boolean passed;
        String message;

        CompletenessSampleResult(boolean passed, String message) {
            this.passed = passed;
            this.message = message;
        }
    }

    /**
     * 抽样验证单只股票的复权因子记录完整性。
     * <p>
     * 逻辑：每只股票在 [上市日, 最新交易日]（或 [上市日, 退市日]）之间的每个交易日，
     * 理论上都应该有一条复权因子记录。因此实际记录数应等于该区间内的交易日数量。
     * <p>
     * 通过抽样 N 只股票比较 actual_count vs expected_trade_days 来评估整体完整性。
     * 只需要 count，不需要逐天比对，计算高效。
     *
     * @param latestDate adj_factor 表中的最新交易日
     */
    private CompletenessSampleResult checkCompletenessBySampling(String latestDate) {
        // 1. 取所有在市股票（上市状态 L，且有上市日期）
        List<StockBasicDO> listedStocks = stockBasicMapper.selectList(
                new LambdaQueryWrapper<StockBasicDO>()
                        .eq(StockBasicDO::getListStatus, ListStatusEnum.LISTED)
                        .isNotNull(StockBasicDO::getListDate));

        if (listedStocks.isEmpty()) {
            return new CompletenessSampleResult(true, "无在市股票数据，跳过");
        }

        // 2. 随机抽样
        List<StockBasicDO> shuffled = new ArrayList<>(listedStocks);
        Collections.shuffle(shuffled);
        int sampleSize = Math.min(COMPLETENESS_SAMPLE_SIZE, shuffled.size());
        List<StockBasicDO> samples = shuffled.subList(0, sampleSize);

        // 3. 取样本中最早的上市日期，一次性拉取 [最早上市日, latestDate] 的所有交易日
        String earliestListDate = samples.stream()
                .map(StockBasicDO::getListDate)
                .filter(Objects::nonNull)
                .min(String::compareTo)
                .orElse(latestDate);

        List<TradeCalDTO> allTradeDays = tradeCalService.queryLocal(
                "SSE", earliestListDate, latestDate, "1");
        if (allTradeDays == null || allTradeDays.isEmpty()) {
            return new CompletenessSampleResult(true, "交易日历无数据，跳过");
        }
        List<String> tradeDateList = allTradeDays.stream()
                .map(TradeCalDTO::getCalDate)
                .sorted()
                .toList();

        // 4. 批量查询样本股票在各自日期范围内的实际记录数
        List<String> sampleCodes = samples.stream()
                .map(StockBasicDO::getTsCode)
                .toList();
        // 用全局日期范围查询（比逐只查询高效），后续按每只股票的上市日单独计数
        List<Map<String, Object>> actualCounts = adjFactorMapper.countByTsCodesInRange(
                sampleCodes, earliestListDate, latestDate);
        Map<String, Integer> actualCountMap = new HashMap<>();
        for (Map<String, Object> row : actualCounts) {
            actualCountMap.put(
                    (String) row.get("ts_code"),
                    ((Number) row.get("cnt")).intValue());
        }

        // 5. 逐只比对：预期交易日数 vs 实际记录数
        int passCount = 0;
        String worstStock = null;
        double worstRatio = 1.0;
        int worstExpected = 0;
        int worstActual = 0;

        for (StockBasicDO stock : samples) {
            String listDate = stock.getListDate();
            if (listDate == null) continue;

            // 预期交易日数：[listDate, latestDate] 区间内的交易日数量
            long expected = tradeDateList.stream()
                    .filter(d -> d.compareTo(listDate) >= 0 && d.compareTo(latestDate) <= 0)
                    .count();

            if (expected == 0) continue;

            int actual = actualCountMap.getOrDefault(stock.getTsCode(), 0);
            double ratio = (double) actual / expected;

            if (ratio >= COMPLETENESS_PASS_RATIO) {
                passCount++;
            }

            if (ratio < worstRatio) {
                worstRatio = ratio;
                worstStock = stock.getTsCode();
                worstExpected = (int) expected;
                worstActual = actual;
            }
        }

        int totalSampled = (int) samples.stream()
                .filter(s -> s.getListDate() != null)
                .count();
        int samplePassPct = totalSampled > 0 ? (int) ((double) passCount / totalSampled * 100) : 100;
        boolean passed = passCount == totalSampled;

        String msg;
        if (passed) {
            msg = "通过，抽样 " + totalSampled + " 只股票完整率均 ≥ "
                    + (int) (COMPLETENESS_PASS_RATIO * 100) + "%";
        } else {
            int worstPct = (int) (worstRatio * 100);
            msg = "抽样 " + totalSampled + " 只，" + passCount + " 只达标（" + samplePassPct
                    + "%），最差 " + worstStock + "：" + worstActual + " / " + worstExpected
                    + " 条（" + worstPct + "%）";
        }

        return new CompletenessSampleResult(passed, msg);
    }
}