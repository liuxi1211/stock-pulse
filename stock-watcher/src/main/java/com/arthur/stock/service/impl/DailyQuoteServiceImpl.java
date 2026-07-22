package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.constant.ListStatusEnum;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.dto.tushare.DailyQueryDTO;
import com.arthur.stock.dto.tushare.DailyQuoteDTO;
import com.arthur.stock.service.DailyQuoteService;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.TradeCalendarService;
import com.arthur.stock.util.SensitiveDataUtil;
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
 * 日线行情服务实现类，负责从Tushare获取日线数据并持久化到本地数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyQuoteServiceImpl implements DailyQuoteService, DataCheckable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int PAGE_SIZE = 6000;
    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final DailyQuoteMapper dailyQuoteMapper;
    private final StockBasicMapper stockBasicMapper;
    private final TradeCalendarService tradeCalendarService;

    /**
     * 查询指定股票在日期范围内的日线行情（仅从Tushare获取，不保存）
     */
    @Override
    public List<DailyQuoteDTO> queryByCodeAndDateRange(String tsCode, String startDate, String endDate) {
        DailyQueryDTO param = DailyQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        return fetchAllPages(param);
    }

    /**
     * 查询指定交易日期的全市场日线行情（仅从Tushare获取，不保存）
     */
    @Override
    public List<DailyQuoteDTO> queryByTradeDate(String tradeDate) {
        DailyQueryDTO param = DailyQueryDTO.builder()
                .tradeDate(tradeDate)
                .build();
        return fetchAllPages(param);
    }

    /**
     * 从Tushare增量获取日线数据并保存到本地数据库，
     * 增量起点为该股票在数据库中的最新交易日期+1天
     */
    @Override
    public List<DailyQuoteDTO> fetchAndSaveDailyQuotes(String tsCode) {
        String lastDate = getLastTradeDate(tsCode);

        String startDate;
        if (lastDate != null) {
            LocalDate ld = LocalDate.parse(lastDate, DATE_FMT);
            startDate = ld.plusDays(1).format(DATE_FMT);
        } else {
            startDate = LocalDate.now().minusYears(30).format(DATE_FMT);
        }

        String endDate = LocalDate.now().format(DATE_FMT);

        if (startDate.compareTo(endDate) > 0) {
            log.info("Stock {} data is up to date", tsCode);
            return Collections.emptyList();
        }

        log.info("Fetching daily quotes for {} from {} to {}", tsCode, startDate, endDate);

        DailyQueryDTO param = DailyQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<DailyQuoteDTO> quotes = fetchAllPages(param);

        if (quotes.isEmpty()) {
            log.info("No daily data returned for {}", tsCode);
            return Collections.emptyList();
        }

        List<DailyQuoteDO> entities = quotes.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveQuotes(entities);
        log.info("Saved {} daily quotes for {}", entities.size(), tsCode);
        return quotes;
    }

    /**
     * 按交易日期从Tushare获取全市场日线数据并保存到本地数据库
     */
    @Override
    public List<DailyQuoteDTO> fetchAndSaveByTradeDate(String tradeDate) {
        log.info("Fetching daily quotes for trade_date={}", tradeDate);

        DailyQueryDTO param = DailyQueryDTO.builder()
                .tradeDate(tradeDate)
                .build();
        List<DailyQuoteDTO> quotes = fetchAllPages(param);

        if (quotes.isEmpty()) {
            log.info("No daily data returned for trade_date={}", tradeDate);
            return Collections.emptyList();
        }

        List<DailyQuoteDO> entities = quotes.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveQuotes(entities);
        log.info("Saved {} daily quotes for trade_date={}", entities.size(), tradeDate);
        return quotes;
    }

    /**
     * 统计日期范围内每个交易日有多少只股票有行情数据
     */
    @Override
    public Map<String, Integer> getTradeDateStockCounts(String startDate, String endDate) {
        List<Map<String, Object>> rows = dailyQuoteMapper.selectTradeDateStockCount(startDate, endDate);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("trade_date"), ((Number) row.get("cnt")).intValue());
        }
        return result;
    }

    /**
     * 从本地数据库查询指定股票的全部日线数据（按日期升序）
     */
    @Override
    public List<DailyQuoteDO> queryLocalByTsCode(String tsCode) {
        return dailyQuoteMapper.selectList(
                new LambdaQueryWrapper<DailyQuoteDO>()
                        .eq(DailyQuoteDO::getTsCode, tsCode)
                        .orderByAsc(DailyQuoteDO::getTradeDate));
    }

    /**
     * 批量取多只股票末 N 个交易日的 OHLCV。
     * <p>
     * 策略：以最新交易日为锚，向前回溯 {@code recentBars * 2} 个自然日（保守覆盖停牌/节假日），
     * 一次性 {@code IN} 查询 + 内存按 ts_code 分组 + 末 recentBars 根裁剪。
     * 依赖 daily_quote 主键索引 (ts_code, trade_date)。
     */
    @Override
    public Map<String, List<DailyQuoteDO>> queryRecentOhlcvByCodes(List<String> codes, int recentBars) {
        if (codes == null || codes.isEmpty() || recentBars <= 0) {
            return Collections.emptyMap();
        }
        String latest = tradeCalendarService.getLatestTradeDate();
        if (latest == null || latest.length() != 8) {
            log.warn("queryRecentOhlcvByCodes: 最新交易日缺失，返回空");
            return Collections.emptyMap();
        }
        LocalDate latestDate = LocalDate.parse(latest, DATE_FMT);
        // recentBars * 2 个自然日回溯（约覆盖 1.4 倍交易日，含节假日冗余）
        String startDate = latestDate.minusDays((long) recentBars * 2).format(DATE_FMT);

        List<DailyQuoteDO> rows = dailyQuoteMapper.selectOhlcvByCodesAndDateRange(codes, startDate, latest);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }

        // 按 ts_code 分组（保留升序），每组取末 recentBars 根
        Map<String, List<DailyQuoteDO>> grouped = rows.stream()
                .collect(Collectors.groupingBy(DailyQuoteDO::getTsCode, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<DailyQuoteDO>> result = new LinkedHashMap<>(grouped.size());
        for (Map.Entry<String, List<DailyQuoteDO>> e : grouped.entrySet()) {
            List<DailyQuoteDO> list = e.getValue();
            if (list.size() > recentBars) {
                list = list.subList(list.size() - recentBars, list.size());
            }
            result.put(e.getKey(), list);
        }
        return result;
    }

    /**
     * 分页拉取Tushare日线数据，自动处理分页直到所有数据获取完毕
     */
    private List<DailyQuoteDTO> fetchAllPages(DailyQueryDTO baseParam) {
        List<DailyQuoteDTO> allRows = new ArrayList<>();
        int offset = 0;

        while (true) {
            DailyQueryDTO param = DailyQueryDTO.builder()
                    .tsCode(baseParam.getTsCode())
                    .tradeDate(baseParam.getTradeDate())
                    .startDate(baseParam.getStartDate())
                    .endDate(baseParam.getEndDate())
                    .offset(offset)
                    .limit(PAGE_SIZE)
                    .build();

            List<DailyQuoteDTO> page = tushareClient.daily(param);
            if (page.isEmpty()) {
                break;
            }
            allRows.addAll(page);
            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }

        return allRows;
    }

    /**
     * 查询指定股票在本地数据库中最新的交易日期
     */
    private String getLastTradeDate(String tsCode) {
        DailyQuoteDO last = dailyQuoteMapper.selectOne(
                new LambdaQueryWrapper<DailyQuoteDO>()
                        .eq(DailyQuoteDO::getTsCode, tsCode)
                        .orderByDesc(DailyQuoteDO::getTradeDate)
                        .last("LIMIT 1"));
        return last != null ? last.getTradeDate() : null;
    }

    private DailyQuoteDO toEntity(DailyQuoteDTO dto) {
        return DailyQuoteDO.builder()
                .tsCode(dto.getTsCode())
                .tradeDate(dto.getTradeDate())
                .open(dto.getOpen())
                .high(dto.getHigh())
                .low(dto.getLow())
                .close(dto.getClose())
                .preClose(dto.getPreClose())
                .changeAmt(dto.getChange())
                .pctChg(dto.getPctChg())
                .vol(dto.getVol())
                .amount(dto.getAmount())
                .build();
    }

    /**
     * 批量保存日线数据。先删除同主键（ts_code+trade_date）已存在记录，再插入；跨方言通用。
     */
    private void saveQuotes(List<DailyQuoteDO> quotes) {
        Lists.partition(quotes, BATCH_SIZE).forEach(batch -> {
            dailyQuoteMapper.deleteBatchByKeys(batch);
            dailyQuoteMapper.insertBatch(batch);
        });
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.DAILY.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = dailyQuoteMapper.selectCount(null);
            String latestDate = dailyQuoteMapper.selectLatestTradeDate();
            LocalDate today = LocalDate.now();
            String todayStr = today.format(DATE_FMT);

            // Check 1: Freshness (use trade calendar to determine last expected trade day)
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

            // Check 2: Data volume anomaly
            String sevenDaysAgo = today.minusDays(7).format(DATE_FMT);
            String twentySevenDaysAgo = today.minusDays(27).format(DATE_FMT);
            String eightDaysAgo = today.minusDays(8).format(DATE_FMT);
            Map<String, Object> stats = dailyQuoteMapper.selectDailyCountStats(sevenDaysAgo, todayStr, twentySevenDaysAgo, eightDaysAgo);
            double recentAvg = getDouble(stats, "recent_avg");
            double prevAvg = getDouble(stats, "prev_avg");
            boolean volumePassed = prevAvg == 0 || recentAvg >= prevAvg * 0.7;
            String volumeMsg;
            if (volumePassed) {
                volumeMsg = "通过，最近 7 天日均 " + (long) recentAvg + " 条，稳定";
            } else {
                double dropPct = (1 - recentAvg / prevAvg) * 100;
                volumeMsg = "最近 7 天日均数据量仅 " + (long) recentAvg + " 条，较前 20 天下降 " + String.format("%.1f", dropPct) + "%";
            }
            items.add(DataCheckItem.builder()
                    .name("data_volume")
                    .displayName("数据量异常检测")
                    .passed(volumePassed)
                    .level(CheckLevel.ERROR)
                    .message(volumeMsg)
                    .build());

            // Check 3: Price logic
            String thirtyDaysAgo = today.minusDays(30).format(DATE_FMT);
            int priceAnomalies = dailyQuoteMapper.countPriceAnomalies(thirtyDaysAgo);
            items.add(DataCheckItem.builder()
                    .name("price_logic")
                    .displayName("价格逻辑检测")
                    .passed(priceAnomalies == 0)
                    .level(CheckLevel.ERROR)
                    .message(priceAnomalies == 0 ? "通过，最近 30 天无异常" : "最近 30 天价格异常记录 " + priceAnomalies + " 条")
                    .build());

            // Check 4: Close price beyond limit
            int beyondLimit = dailyQuoteMapper.countCloseBeyondLimit(thirtyDaysAgo);
            items.add(DataCheckItem.builder()
                    .name("close_beyond_limit")
                    .displayName("收盘价超涨跌停检测")
                    .passed(beyondLimit == 0)
                    .level(CheckLevel.WARN)
                    .message(beyondLimit == 0 ? "通过，最近 30 天收盘价均在涨跌停范围内" : "最近 30 天收盘价超出涨跌停 " + beyondLimit + " 条")
                    .build());

            // Check 5: Coverage
            int dailyCount = dailyQuoteMapper.countDistinctStocksOnDate(latestDate != null ? latestDate : todayStr);
            long listedCount = stockBasicMapper.selectCount(
                    new LambdaQueryWrapper<StockBasicDO>().eq(StockBasicDO::getListStatus, ListStatusEnum.LISTED));
            double coverage = listedCount > 0 ? (double) dailyCount / listedCount : 0;
            int coveragePct = (int) (coverage * 100);
            boolean coveragePassed = coverage >= 0.9;
            items.add(DataCheckItem.builder()
                    .name("coverage")
                    .displayName("覆盖度检测")
                    .passed(coveragePassed)
                    .level(CheckLevel.WARN)
                    .message(coveragePassed ? "通过，当日覆盖度 " + coveragePct + "%" : "当日股票覆盖度仅 " + coveragePct + "%")
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.DAILY.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for daily_quote", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + SensitiveDataUtil.mask(e.getMessage()))
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.DAILY.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }

    private double getDouble(Map<String, Object> map, String key) {
        if (map == null) return 0.0;
        Object val = map.get(key);
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return 0.0; }
    }
}