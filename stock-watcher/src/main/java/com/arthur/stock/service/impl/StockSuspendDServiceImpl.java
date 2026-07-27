package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.ExchangeEnum;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.SuspendDDTO;
import com.arthur.stock.dto.tushare.SuspendDQueryDTO;
import com.arthur.stock.dto.tushare.TradeCalDTO;
import com.arthur.stock.mapper.StockSuspendDMapper;
import com.arthur.stock.model.StockSuspendDDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.StockBasicService;
import com.arthur.stock.service.StockSuspendDService;
import com.arthur.stock.service.TradeCalService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 股票停复牌服务实现（事件模型：S=停牌，R=复牌）。
 * <p>
 * 数据源：tushare suspend_d（doc_id=161），单次最大 5000 行（分页）。
 * 落库策略：按业务键 (ts_code, trade_date, suspend_type) 批量 delete-then-insert，保证幂等。
 * PK 为 (ts_code, trade_date, suspend_type)，允许同一股票同日存在 S 和 R 两条事件。
 * 停牌日期推导：基于 S/R 事件序列，用状态机计算每日实际停牌状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockSuspendDServiceImpl implements StockSuspendDService, DataCheckable {

    private static final int PAGE_SIZE = 5000;
    private static final int BATCH_SIZE = 500;
    /** Tushare suspend_d 接口 offset 上限 100000，月内超此阈值则降级为按股票拉取 */
    private static final int OFFSET_LIMIT = 100000;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TushareClient tushareClient;
    private final StockSuspendDMapper stockSuspendDMapper;
    private final TradeCalService tradeCalService;
    private final StockBasicService stockBasicService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 启动时自动迁移：将 suspend_timing 列从 VARCHAR(32) 扩展为 VARCHAR(128)。
     * <p>
     * Tushare suspend_d 接口的 suspend_timing 字段可包含多个盘中停牌时段（如 09:30-10:31,14:29-14:57），
     * VARCHAR(32) 不足以容纳，会导致 Data truncation 错误。
     */
    @PostConstruct
    public void migrateSuspendTimingColumnLength() {
        try {
            Integer charLength = jdbcTemplate.queryForObject(
                    "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_suspend_d' " +
                            "AND COLUMN_NAME = 'suspend_timing'",
                    Integer.class);
            if (charLength != null && charLength >= 128) {
                return;
            }
            log.info("stock_suspend_d: altering suspend_timing VARCHAR({}) -> VARCHAR(128)", charLength);
            jdbcTemplate.execute("ALTER TABLE stock_suspend_d MODIFY COLUMN suspend_timing VARCHAR(128)");
            log.info("stock_suspend_d: suspend_timing column migrated to VARCHAR(128)");
        } catch (Exception e) {
            log.error("stock_suspend_d: suspend_timing column migration failed", e);
        }
    }

    /** A股市场最早日期（上交所1990年12月成立），用于全量拉取时按月拆分 */
    private static final String EARLIEST_DATE = "19901201";

    @Override
    public int fetchAndSaveAll() {
        String endDate = LocalDate.now().format(DATE_FMT);
        log.info("Fetching stock_suspend_d full (month-by-month): {}~{}", EARLIEST_DATE, endDate);
        int total = fetchAndSaveByMonth(EARLIEST_DATE, endDate);
        log.info("Saved {} stock_suspend_d records (full)", total);
        return total;
    }

    @Override
    public int fetchAndSaveByRange(String startDate, String endDate) {
        log.info("Fetching stock_suspend_d by range (month-by-month): {}~{}", startDate, endDate);
        int total = fetchAndSaveByMonth(startDate, endDate);
        log.info("Saved {} stock_suspend_d records for range {}~{}", total, startDate, endDate);
        return total;
    }

    /**
     * 按月拆分拉取：每个月内用分页拉取，若当月数据量超过 offset 上限（10w），
     * 则降级为按股票逐只拉取，确保数据完整。
     * <p>
     * 为什么按月？Tushare suspend_d 接口 offset 上限 10w，且返回顺序为 ts_code + trade_date
     * （先按股票排，再按日期排）。按月拆分后单月数据量远低于 10w（全表 70w / 360 月 ≈ 2000/月），
     * 正常情况下可安全翻页；极端月份（如股灾月）若超 10w，自动降级按股票拉取兜底。
     *
     * @param startDate 起始日期 yyyyMMdd（含）
     * @param endDate   结束日期 yyyyMMdd（含）
     * @return 落库记录数
     */
    private int fetchAndSaveByMonth(String startDate, String endDate) {
        int total = 0;
        LocalDate start = LocalDate.parse(startDate, DATE_FMT);
        LocalDate end = LocalDate.parse(endDate, DATE_FMT);
        LocalDate cursor = start.withDayOfMonth(1);

        while (!cursor.isAfter(end)) {
            String mStart = cursor.format(DATE_FMT);
            String mEnd = cursor.withDayOfMonth(cursor.lengthOfMonth()).format(DATE_FMT);
            // 首月/末月对齐实际起止
            if (cursor.getMonth() == start.getMonth() && cursor.getYear() == start.getYear()) {
                mStart = startDate;
            }
            if (cursor.getMonth() == end.getMonth() && cursor.getYear() == end.getYear()) {
                mEnd = endDate;
            }

            int saved = fetchAndSaveMonth(mStart, mEnd);
            total += saved;

            cursor = cursor.plusMonths(1).withDayOfMonth(1);
        }
        return total;
    }

    /**
     * 单月数据拉取：先尝试按月分页拉取；若触发 offset 上限（数据量 >10w），
     * 则放弃按月结果，降级为按股票逐只拉取，保证数据完整性。
     *
     * @param mStart 月内起始日期 yyyyMMdd
     * @param mEnd   月内结束日期 yyyyMMdd
     * @return 落库记录数
     */
    private int fetchAndSaveMonth(String mStart, String mEnd) {
        SuspendDQueryDTO param = SuspendDQueryDTO.builder()
                .startDate(mStart)
                .endDate(mEnd)
                .build();
        int total = 0;
        int offset = 0;
        boolean hitLimit = false;

        while (true) {
            List<SuspendDDTO> page = tushareClient.suspendD(param, offset, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            int saved = transactionTemplate.execute(status -> persistByBizKey(page));
            total += saved;

            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;

            // 触发 offset 上限 → 降级按股票拉取
            if (offset >= OFFSET_LIMIT) {
                hitLimit = true;
                break;
            }
        }

        if (hitLimit) {
            log.warn("stock_suspend_d {}~{} hit offset limit ({}), falling back to per-stock fetch",
                    mStart, mEnd, OFFSET_LIMIT);
            total = fetchAndSavePerStock(mStart, mEnd);
        }

        if (total > 0) {
            log.info("stock_suspend_d {}~{} completed: saved {} records{}",
                    mStart, mEnd, total, hitLimit ? " (via per-stock fallback)" : "");
        }
        return total;
    }

    /**
     * 按股票逐只拉取指定日期区间的数据（降级兜底方案）。
     * <p>
     * 单只股票一个月内的停复牌事件极少（通常 0~5 条），远低于 5000 行上限，
     * 无需分页，一次拉取即可。
     *
     * @param startDate 起始日期 yyyyMMdd
     * @param endDate   结束日期 yyyyMMdd
     * @return 落库记录数
     */
    private int fetchAndSavePerStock(String startDate, String endDate) {
        List<String> tsCodes = stockBasicService.queryLocal(null, null, null, "L")
                .stream()
                .map(dto -> dto.getTsCode())
                .filter(code -> code != null && !code.isEmpty())
                .collect(Collectors.toList());
        if (tsCodes.isEmpty()) {
            log.warn("stock_suspend_d per-stock fallback: no stocks found in stock_basic");
            return 0;
        }

        int total = 0;
        int done = 0;
        for (String tsCode : tsCodes) {
            List<SuspendDDTO> rows = tushareClient.suspendD(
                    SuspendDQueryDTO.builder()
                            .tsCode(tsCode)
                            .startDate(startDate)
                            .endDate(endDate)
                            .build(),
                    null, null);
            if (!rows.isEmpty()) {
                int saved = transactionTemplate.execute(status -> persistByBizKey(rows));
                total += saved;
            }
            done++;
            if (done % 500 == 0 || done == tsCodes.size()) {
                log.info("stock_suspend_d per-stock fallback {}~{}: {}/{} stocks, saved {}",
                        startDate, endDate, done, tsCodes.size(), total);
            }
        }
        return total;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveIncremental(String tradeDate) {
        log.info("Fetching stock_suspend_d incremental for tradeDate={}", tradeDate);
        List<SuspendDDTO> rows = tushareClient.suspendD(
                SuspendDQueryDTO.builder().startDate(tradeDate).endDate(tradeDate).build(), null, null);
        int total = persistByBizKey(rows);
        log.info("Saved {} incremental stock_suspend_d records for {}", total, tradeDate);
        return total;
    }

    @Override
    public Map<String, Set<String>> listSuspendDates(List<String> tsCodes, String startDate, String endDate) {
        if (tsCodes == null || tsCodes.isEmpty() || startDate == null || endDate == null) {
            return Collections.emptyMap();
        }

        List<StockSuspendDDO> allEvents = stockSuspendDMapper.selectEventsByTsCodesUpToDate(tsCodes, endDate);
        if (allEvents.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<StockSuspendDDO>> eventsByCode = allEvents.stream()
                .collect(Collectors.groupingBy(StockSuspendDDO::getTsCode, LinkedHashMap::new, Collectors.toList()));

        List<String> tradeDates = resolveTradeDates(tsCodes, startDate, endDate);
        if (tradeDates.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String tsCode : tsCodes) {
            List<StockSuspendDDO> events = eventsByCode.get(tsCode);
            Set<String> suspDates = computeSuspendDates(events, tradeDates);
            if (!suspDates.isEmpty()) {
                result.put(tsCode, suspDates);
            }
        }
        return result;
    }

    private Set<String> computeSuspendDates(List<StockSuspendDDO> events, List<String> tradeDates) {
        Set<String> suspDates = new LinkedHashSet<>();
        if (tradeDates.isEmpty()) {
            return suspDates;
        }

        Map<String, String> eventMap = new LinkedHashMap<>();
        if (events != null) {
            for (StockSuspendDDO e : events) {
                if (!isFullDayEvent(e)) {
                    continue;
                }
                eventMap.put(e.getTradeDate(), e.getSuspendType());
            }
        }

        boolean isSuspended = false;
        for (String td : tradeDates) {
            String type = eventMap.get(td);
            if (type != null) {
                if ("S".equals(type)) {
                    isSuspended = true;
                } else if ("R".equals(type)) {
                    isSuspended = false;
                }
            }
            if (isSuspended) {
                suspDates.add(td);
            }
        }
        return suspDates;
    }

    private boolean isFullDayEvent(StockSuspendDDO event) {
        String timing = event.getSuspendTiming();
        return timing == null || timing.isEmpty();
    }

    private List<String> resolveTradeDates(List<String> tsCodes, String startDate, String endDate) {
        Set<String> exchanges = tsCodes.stream()
                .map(this::inferExchange)
                .filter(e -> e != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (exchanges.isEmpty()) {
            exchanges.add(ExchangeEnum.SSE.getCode());
        }
        Set<String> allDates = new LinkedHashSet<>();
        for (String exchange : exchanges) {
            List<TradeCalDTO> calList = tradeCalService.queryLocal(exchange, startDate, endDate, "1");
            for (TradeCalDTO cal : calList) {
                allDates.add(cal.getCalDate());
            }
        }
        return allDates.stream().sorted().collect(Collectors.toList());
    }

    private String inferExchange(String tsCode) {
        if (tsCode == null) {
            return null;
        }
        if (tsCode.endsWith(".SH")) {
            return ExchangeEnum.SSE.getCode();
        } else if (tsCode.endsWith(".SZ")) {
            return ExchangeEnum.SZSE.getCode();
        } else if (tsCode.endsWith(".BJ")) {
            return "BSE";
        }
        return null;
    }

    private int persistByBizKey(List<SuspendDDTO> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        // 按 (ts_code, trade_date, suspend_type) 去重，保留最后一条。
        // 同一股票同日可能存在多条 S/R 事件（如盘中多次临时停牌），去重避免 PK 冲突。
        Map<String, StockSuspendDDO> dedupMap = new LinkedHashMap<>();
        for (SuspendDDTO dto : rows) {
            StockSuspendDDO entity = toEntity(dto);
            if (entity == null) {
                continue;
            }
            String key = entity.getTsCode() + "|" + entity.getTradeDate() + "|" + entity.getSuspendType();
            dedupMap.put(key, entity);
        }
        List<StockSuspendDDO> entities = new ArrayList<>(dedupMap.values());
        int count = 0;
        for (List<StockSuspendDDO> batch : Lists.partition(entities, BATCH_SIZE)) {
            stockSuspendDMapper.deleteBatchByKeys(batch);
            count += stockSuspendDMapper.insertBatch(batch);
        }
        return count;
    }

    private StockSuspendDDO toEntity(SuspendDDTO dto) {
        if (dto == null || dto.getTsCode() == null || dto.getTradeDate() == null) {
            return null;
        }
        String suspendType = dto.getSuspendType();
        if (suspendType == null || suspendType.isEmpty()) {
            return null;
        }
        return StockSuspendDDO.builder()
                .tsCode(dto.getTsCode())
                .tradeDate(dto.getTradeDate())
                .suspendTiming(dto.getSuspendTiming())
                .suspendType(suspendType)
                .build();
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.SUSPEND_D.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = stockSuspendDMapper.selectCount(null);
            String latestDate = stockSuspendDMapper.selectMaxTradeDate();

            if (totalRows == 0) {
                items.add(DataCheckItem.builder()
                        .name("empty_check")
                        .displayName("表空检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
            } else {
                int invalidTypeCount = stockSuspendDMapper.countInvalidType();
                items.add(DataCheckItem.builder()
                        .name("type_validity")
                        .displayName("类型合法性检测")
                        .passed(invalidTypeCount == 0)
                        .level(CheckLevel.ERROR)
                        .message(invalidTypeCount == 0 ? "通过，suspend_type 均为 S/R"
                                : "suspend_type 异常记录 " + invalidTypeCount + " 条（非 S/R）")
                        .build());

                int badSeqCount = countBadSequenceStocks();
                items.add(DataCheckItem.builder()
                        .name("event_sequence")
                        .displayName("事件序列检测")
                        .passed(badSeqCount == 0)
                        .level(CheckLevel.WARN)
                        .message(badSeqCount == 0 ? "通过，事件序列正常"
                                : "事件序列异常股票 " + badSeqCount + " 只（连续 R 无 S 间隔）")
                        .build());

                items.add(DataCheckItem.builder()
                        .name("latest_date_freshness")
                        .displayName("最新日期新鲜度")
                        .passed(isDateFresh(latestDate))
                        .level(CheckLevel.WARN)
                        .message(isDateFresh(latestDate) ? "通过，最新数据在 7 天内"
                                : "最新数据日期 " + latestDate + " 超过 7 天未更新")
                        .build());
            }

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.SUSPEND_D.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for suspend_d", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.SUSPEND_D.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }

    private boolean isDateFresh(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return false;
        }
        try {
            LocalDate latest = LocalDate.parse(dateStr, DATE_FMT);
            return !latest.isBefore(LocalDate.now().minusDays(7));
        } catch (Exception e) {
            return false;
        }
    }

    /** 事件序列检测每批股票数，避免一次性加载全量 70w+ 数据导致内存溢出 */
    private static final int SEQ_CHECK_BATCH_SIZE = 30;

    /**
     * 事件序列异常检测（分批查询，避免内存溢出）。
     * <p>
     * Tushare suspend_d 数据模型：S = 该日处于停牌状态（每日快照，非事件边界），
     * R = 该日复牌。因此连续 S 是正常的（多日停牌每天都会产生一条 S），不应报错。
     * <p>
     * 真正异常的是连续 R（无 S 间隔的两次复牌），因为复牌后必须先停牌才能再次复牌。
     * <p>
     * 注意：序列检测看**所有**事件（包括盘中临时停牌），因为数据完整性校验不区分全天还是盘中；
     * 只有停牌日期推导（listSuspendDates）才会忽略盘中事件。
     * <p>
     * 检测到异常时会打印具体的股票代码和异常日期，便于排查。
     *
     * @return 事件序列异常的股票数
     */
    private int countBadSequenceStocks() {
        List<String> allCodes = stockSuspendDMapper.selectDistinctTsCodes();
        if (allCodes.isEmpty()) {
            return 0;
        }
        Set<String> badStocks = new LinkedHashSet<>();
        int totalBatches = (allCodes.size() + SEQ_CHECK_BATCH_SIZE - 1) / SEQ_CHECK_BATCH_SIZE;

        for (int batch = 0; batch < totalBatches; batch++) {
            int fromIndex = batch * SEQ_CHECK_BATCH_SIZE;
            int toIndex = Math.min(fromIndex + SEQ_CHECK_BATCH_SIZE, allCodes.size());
            List<String> batchCodes = allCodes.subList(fromIndex, toIndex);

            List<StockSuspendDDO> batchEvents = stockSuspendDMapper.selectEventsByTsCodes(batchCodes);
            if (batchEvents.isEmpty()) {
                continue;
            }

            String prevCode = null;
            String prevType = null;
            String prevDate = null;
            for (StockSuspendDDO e : batchEvents) {
                String code = e.getTsCode();
                String type = e.getSuspendType();
                String date = e.getTradeDate();
                if (code == null || type == null) {
                    continue;
                }
                if (!code.equals(prevCode)) {
                    prevCode = code;
                    prevType = type;
                    prevDate = date;
                } else {
                    // 连续 R（无 S 间隔）才是异常：复牌后必须先停牌才能再复牌
                    if ("R".equals(type) && "R".equals(prevType)) {
                        boolean firstTime = badStocks.add(code);
                        if (firstTime) {
                            log.warn("[suspend_d 序列异常] 股票 {} 在 {} 和 {} 连续出现两次 R（复牌），中间无停牌 S",
                                    code, prevDate, date);
                        }
                    }
                    prevType = type;
                    prevDate = date;
                }
            }

            if ((batch + 1) % 50 == 0 || batch == totalBatches - 1) {
                log.info("[suspend_d 序列检测] 进度 {}/{} 批，已检测 {} 只股票，发现异常 {} 只",
                        batch + 1, totalBatches, toIndex, badStocks.size());
            }
        }

        if (!badStocks.isEmpty()) {
            log.warn("[suspend_d 序列检测完成] 共检测 {} 只股票，发现事件序列异常 {} 只，异常股票: {}",
                    allCodes.size(), badStocks.size(), badStocks);
        } else {
            log.info("[suspend_d 序列检测完成] 共检测 {} 只股票，事件序列全部正常", allCodes.size());
        }
        return badStocks.size();
    }
}
