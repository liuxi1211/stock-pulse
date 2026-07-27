package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.ExchangeEnum;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.constant.TradeDayStatusEnum;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.mapper.TradeCalMapper;
import com.arthur.stock.model.TradeCalDO;
import com.arthur.stock.dto.tushare.TradeCalDTO;
import com.arthur.stock.dto.tushare.TradeCalQueryDTO;
import com.arthur.stock.service.TradeCalService;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.util.SensitiveDataUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 交易日历服务实现类，负责从Tushare获取交易日历数据并持久化到本地数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeCalServiceImpl implements TradeCalService, DataCheckable {

    /** DB 批量操作分片大小（delete/insert/update 分批，控制单次 SQL 体积）。 */
    private static final int DB_BATCH_SIZE = 500;

    /** Tushare 分页拉取每页条数（trade_cal 单次返回上限，30 年约 11323 行需多页）。 */
    private static final int BATCH_SIZE = 5000;

    /** 分页拉取最大页数保护，超出即告警（防止异常死循环）。 */
    private static final int MAX_PAGES = 10;

    /** A 股交易所开市日，用于 completeness 校验的起始基准。 */
    private static final LocalDate SSE_OPEN_DATE = LocalDate.of(1990, 12, 19);
    private static final LocalDate SZSE_OPEN_DATE = LocalDate.of(1991, 7, 3);

    private final TushareClient tushareClient;
    private final TradeCalMapper tradeCalMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 从Tushare获取交易日历数据并保存到本地数据库，已存在的记录会更新。
     * <p>
     * 三段式执行（互不共享事务）：
     * <ol>
     *   <li>HTTP 分页拉取（事务外）：Tushare trade_cal 单次返回有上限，30 年日历约 11323 行需分页。</li>
     *   <li>落库（短事务）：按 exchange 分组先删后插。</li>
     *   <li>预计算调仓标记（独立事务）：全表 select + 批量 update。</li>
     * </ol>
     */
    @Override
    public List<TradeCalDTO> fetchAndSaveTradeCal(String exchange, String startDate, String endDate) {
        log.info("Fetching trade_cal from Tushare: exchange={}, startDate={}, endDate={}", exchange, startDate, endDate);

        TradeCalQueryDTO param = TradeCalQueryDTO.builder()
                .exchange(exchange)
                .startDate(startDate)
                .endDate(endDate)
                .build();

        // ① HTTP 分页拉取（事务外）
        List<TradeCalDTO> calendars = new ArrayList<>();
        int offset = 0;
        boolean maybeTruncated = false;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<TradeCalDTO> pageRows = tushareClient.tradeCal(param, offset, BATCH_SIZE);
            if (pageRows == null || pageRows.isEmpty()) {
                break;
            }
            calendars.addAll(pageRows);
            if (pageRows.size() < BATCH_SIZE) {
                break; // 末页，已取完
            }
            offset += BATCH_SIZE;
            maybeTruncated = (page == MAX_PAGES - 1);
        }
        if (maybeTruncated) {
            log.warn("trade_cal 达到 MAX_PAGES={} 上限，可能存在截断；exchange={}, {}~{}",
                    MAX_PAGES, exchange, startDate, endDate);
        }

        if (calendars.isEmpty()) {
            log.info("No trade_cal data returned");
            return Collections.emptyList();
        }

        List<TradeCalDO> entities = calendars.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TradeCalDO::getCalDate))
                .collect(Collectors.toList());

        // ② 落库（短事务，HTTP 拉取已在外部完成）
        transactionTemplate.execute(status -> {
            saveCalendars(entities);
            return null;
        });
        log.info("Saved {} trade_cal records", entities.size());

        // ③ 预计算 6 个调仓标记（周/月/季的 first/last），独立事务，供 engine 调仓日判定使用。
        transactionTemplate.execute(status -> {
            computeAndSaveRebalanceFlags();
            return null;
        });
        return calendars;
    }

    /**
     * 从本地数据库查询交易日历，支持按交易所、日期范围、是否交易日筛选
     */
    @Override
    public List<TradeCalDTO> queryLocal(String exchange, String startDate, String endDate, String isOpen) {
        LambdaQueryWrapper<TradeCalDO> wrapper = new LambdaQueryWrapper<>();
        if (exchange != null && !exchange.isEmpty()) {
            wrapper.eq(TradeCalDO::getExchange, ExchangeEnum.fromCode(exchange));
        }
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(TradeCalDO::getCalDate, startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.le(TradeCalDO::getCalDate, endDate);
        }
        if (isOpen != null && !isOpen.isEmpty()) {
            wrapper.eq(TradeCalDO::getIsOpen, TradeDayStatusEnum.fromCode(isOpen));
        }
        wrapper.orderByAsc(TradeCalDO::getCalDate);

        List<TradeCalDO> calendars = tradeCalMapper.selectList(wrapper);
        return calendars.stream().map(this::toDTO).collect(Collectors.toList());
    }

    private TradeCalDO toEntity(TradeCalDTO dto) {
        return TradeCalDO.builder()
                .exchange(ExchangeEnum.fromCode(dto.getExchange()))
                .calDate(dto.getCalDate())
                .isOpen(TradeDayStatusEnum.fromCode(dto.getIsOpen()))
                .pretradeDate(dto.getPretradeDate())
                .build();
    }

    private TradeCalDTO toDTO(TradeCalDO entity) {
        return TradeCalDTO.builder()
                .exchange(entity.getExchange() != null ? entity.getExchange().getCode() : null)
                .calDate(entity.getCalDate())
                .isOpen(entity.getIsOpen() != null ? entity.getIsOpen().getCode() : null)
                .pretradeDate(entity.getPretradeDate())
                .build();
    }

    /**
     * 批量保存交易日历数据。
     * 按 exchange 分组后分别删除+插入：
     * - 删除用 {@code exchange = ? AND cal_date IN (...)}（单字段 IN）
     * - 插入用多值 INSERT
     */
    private void saveCalendars(List<TradeCalDO> calendars) {
        if (calendars == null || calendars.isEmpty()) {
            return;
        }
        // 按 exchange 分组
        Map<ExchangeEnum, List<TradeCalDO>> byExchange = calendars.stream()
                .filter(d -> d.getExchange() != null)
                .collect(Collectors.groupingBy(TradeCalDO::getExchange));

        for (Map.Entry<ExchangeEnum, List<TradeCalDO>> entry : byExchange.entrySet()) {
            ExchangeEnum exchange = entry.getKey();
            List<TradeCalDO> exCalendars = entry.getValue();

            Lists.partition(exCalendars, DB_BATCH_SIZE).forEach(batch -> {
                List<String> calDates = batch.stream()
                        .map(TradeCalDO::getCalDate)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                if (!calDates.isEmpty()) {
                    tradeCalMapper.deleteByExchangeAndCalDates(exchange, calDates);
                }
                tradeCalMapper.insertBatch(batch);
            });
        }
    }

    private static final DateTimeFormatter CAL_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 批量查询指定交易所、指定日期范围内（仅 is_open=1）的调仓标记，按 cal_date 建索引返回。
     * <p>
     * 必须指定 exchange，避免 SSE/SZSE 混在一起 key 冲突（不同交易所同日期的标记
     * 理论上一致，但为数据一致性显式约束）。
     *
     * @param exchange  交易所代码（SSE/SZSE），必填
     * @param startDate 开始日期 yyyyMMdd（含，可选，null 表示不限）
     * @param endDate   结束日期 yyyyMMdd（含，可选，null 表示不限）
     * @return key=cal_date(yyyyMMdd)，value=含 6 个标记字段的 TradeCalDO
     */
    @Override
    public Map<String, TradeCalDO> queryFlagsByRange(String exchange, String startDate, String endDate) {
        if (exchange == null || exchange.isEmpty()) {
            throw new IllegalArgumentException("exchange 必填");
        }
        LambdaQueryWrapper<TradeCalDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TradeCalDO::getExchange, ExchangeEnum.fromCode(exchange));
        wrapper.eq(TradeCalDO::getIsOpen, TradeDayStatusEnum.OPEN);
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(TradeCalDO::getCalDate, startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.le(TradeCalDO::getCalDate, endDate);
        }
        wrapper.orderByAsc(TradeCalDO::getCalDate);

        List<TradeCalDO> rows = tradeCalMapper.selectList(wrapper);
        Map<String, TradeCalDO> result = new LinkedHashMap<>(rows.size() * 2);
        for (TradeCalDO row : rows) {
            result.put(row.getCalDate(), row);
        }
        return result;
    }

    /**
     * 预计算并持久化 6 个调仓标记：周/月/季的 first/last 交易日。
     * <p>
     * 按交易所分组独立计算（SSE/SZSE 交易日可能有细微差异），每组内：
     * 取所有 is_open=1 的记录（升序），分别按周/月/季分组，
     * 每组内 cal_date 最小者标 first=1，最大者标 last=1，其余为 0。
     * <ul>
     *   <li>周：采用 ISO-8601 周（周一为周首），用 {@code IsoFields.WEEK_BASED_YEAR} + {@code WEEK_OF_WEEK_BASED_YEAR}
     *       作为分组 key，正确处理跨年周。</li>
     *   <li>月：year + month。</li>
     *   <li>季：(month-1)/3 + 1，year + quarter。</li>
     * </ul>
     * 计算后逐条 update（trade_cal 为低频全量初始化数据，单条 update 可接受；
     * 且单条 SQL 通用）。
     */
    void computeAndSaveRebalanceFlags() {
        LambdaQueryWrapper<TradeCalDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TradeCalDO::getIsOpen, TradeDayStatusEnum.OPEN)
                .orderByAsc(TradeCalDO::getExchange, TradeCalDO::getCalDate);
        List<TradeCalDO> allOpenDays = tradeCalMapper.selectList(wrapper);
        if (allOpenDays.isEmpty()) {
            log.warn("computeAndSaveRebalanceFlags: no open trade days, skip");
            return;
        }

        // 按 exchange 分组
        Map<String, List<TradeCalDO>> byExchange = allOpenDays.stream()
                .collect(Collectors.groupingBy(
                        day -> day.getExchange() != null ? day.getExchange().getCode() : "UNKNOWN",
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        int totalUpdated = 0;
        int totalProcessed = 0;

        for (Map.Entry<String, List<TradeCalDO>> entry : byExchange.entrySet()) {
            String exchange = entry.getKey();
            List<TradeCalDO> openDays = entry.getValue();

            // 分组桶：记录每组当前见到的最早/最晚 cal_date（按字符串字典序与时间序一致，yyyyMMdd 补零）
            Map<String, String> weekFirst = new HashMap<>();
            Map<String, String> weekLast = new HashMap<>();
            Map<String, String> monthFirst = new HashMap<>();
            Map<String, String> monthLast = new HashMap<>();
            Map<String, String> quarterFirst = new HashMap<>();
            Map<String, String> quarterLast = new HashMap<>();

            for (TradeCalDO day : openDays) {
                LocalDate d = parseCalDate(day.getCalDate());
                if (d == null) {
                    continue;
                }

                String weekKey = d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                updateBoundDate(weekFirst, weekLast, weekKey, day.getCalDate());

                String monthKey = d.getYear() + "-" + d.getMonthValue();
                updateBoundDate(monthFirst, monthLast, monthKey, day.getCalDate());

                int quarter = (d.getMonthValue() - 1) / 3 + 1;
                String quarterKey = d.getYear() + "-Q" + quarter;
                updateBoundDate(quarterFirst, quarterLast, quarterKey, day.getCalDate());
            }

            // 回填标记到每个对象
            List<TradeCalDO> toUpdate = new ArrayList<>(openDays.size());
            for (TradeCalDO day : openDays) {
                LocalDate d = parseCalDate(day.getCalDate());
                if (d == null) {
                    continue;
                }
                String weekKey = d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                String monthKey = d.getYear() + "-" + d.getMonthValue();
                int quarter = (d.getMonthValue() - 1) / 3 + 1;
                String quarterKey = d.getYear() + "-Q" + quarter;

                day.setIsFirstOfWeek(isBoundDate(weekFirst, weekKey, day.getCalDate()));
                day.setIsLastOfWeek(isBoundDate(weekLast, weekKey, day.getCalDate()));
                day.setIsFirstOfMonth(isBoundDate(monthFirst, monthKey, day.getCalDate()));
                day.setIsLastOfMonth(isBoundDate(monthLast, monthKey, day.getCalDate()));
                day.setIsFirstOfQuarter(isBoundDate(quarterFirst, quarterKey, day.getCalDate()));
                day.setIsLastOfQuarter(isBoundDate(quarterLast, quarterKey, day.getCalDate()));
                toUpdate.add(day);
            }

            // 批量 update（CASE WHEN 构造，跨方言通用）；分批降低单次 SQL 体积
            int updated = 0;
            for (List<TradeCalDO> batch : Lists.partition(toUpdate, DB_BATCH_SIZE)) {
                updated += tradeCalMapper.updateRebalanceFlagsBatch(batch);
            }
            totalUpdated += updated;
            totalProcessed += openDays.size();
            log.info("computeAndSaveRebalanceFlags: exchange={}, processed={} open days, updated={} rows",
                    exchange, openDays.size(), updated);
        }

        log.info("computeAndSaveRebalanceFlags: total processed={} open days, total updated={} rows",
                totalProcessed, totalUpdated);
    }

    /** 维护某分组的最早/最晚 cal_date（按字符串字典序，yyyyMMdd 补零保证与时间序一致）。 */
    private void updateBoundDate(Map<String, String> firstMap, Map<String, String> lastMap,
                                 String key, String calDate) {
        String curFirst = firstMap.get(key);
        if (curFirst == null || calDate.compareTo(curFirst) < 0) {
            firstMap.put(key, calDate);
        }
        String curLast = lastMap.get(key);
        if (curLast == null || calDate.compareTo(curLast) > 0) {
            lastMap.put(key, calDate);
        }
    }

    /** 判断 calDate 是否为某分组某端点（first 或 last）的边界值（按字符串相等比较，不依赖对象引用）。 */
    private boolean isBoundDate(Map<String, String> boundMap, String key, String calDate) {
        String bound = boundMap.get(key);
        return bound != null && bound.equals(calDate);
    }

    private LocalDate parseCalDate(String calDate) {
        if (calDate == null || calDate.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(calDate, CAL_DATE_FMT);
        } catch (Exception e) {
            log.warn("parseCalDate failed: {}", calDate, e);
            return null;
        }
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.TRADE_CAL.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = tradeCalMapper.selectCount(null);
            LocalDate today = LocalDate.now();
            // 以每日 18:00 为界：18:00 前期望最新交易日为昨天，18:00 后期望为今天
            LocalDateTime now = LocalDateTime.now();
            LocalDate expectedEndDate = now.getHour() >= 18 ? today : today.minusDays(1);
            String expectedEndCalDate = expectedEndDate.format(CAL_DATE_FMT);

            // Check 1: calendar completeness (ERROR)
            // trade_cal 表应包含开市日至今的全部日历日（含周末/节假日，is_open=0 也有记录）。
            // 按 exchange 分别比对预期天数与实际记录数，任一不一致即判失败。
            long sseExpected = ChronoUnit.DAYS.between(SSE_OPEN_DATE, expectedEndDate) + 1;
            long sseActual = tradeCalMapper.selectCount(
                    new LambdaQueryWrapper<TradeCalDO>()
                            .eq(TradeCalDO::getExchange, ExchangeEnum.SSE)
                            .ge(TradeCalDO::getCalDate, SSE_OPEN_DATE.format(CAL_DATE_FMT))
                            .le(TradeCalDO::getCalDate, expectedEndCalDate));
            long szseExpected = ChronoUnit.DAYS.between(SZSE_OPEN_DATE, expectedEndDate) + 1;
            long szseActual = tradeCalMapper.selectCount(
                    new LambdaQueryWrapper<TradeCalDO>()
                            .eq(TradeCalDO::getExchange, ExchangeEnum.SZSE)
                            .ge(TradeCalDO::getCalDate, SZSE_OPEN_DATE.format(CAL_DATE_FMT))
                            .le(TradeCalDO::getCalDate, expectedEndCalDate));
            boolean completenessPassed = sseExpected == sseActual && szseExpected == szseActual;
            items.add(DataCheckItem.builder()
                    .name("calendar_completeness")
                    .displayName("交易日历完整性")
                    .passed(completenessPassed)
                    .level(CheckLevel.ERROR)
                    .message(String.format("SSE 预期 %d / 实际 %d%s；SZSE 预期 %d / 实际 %d%s",
                            sseExpected, sseActual,
                            sseExpected == sseActual ? "" : "(差 " + Math.abs(sseExpected - sseActual) + ")",
                            szseExpected, szseActual,
                            szseExpected == szseActual ? "" : "(差 " + Math.abs(szseExpected - szseActual) + ")"))
                    .build());

            // Check 2: SSE/SZSE consistency last 30 days (WARN)
            String thirtyDaysAgo = today.minusDays(30).format(CAL_DATE_FMT);
            int inconsistency = tradeCalMapper.countSseSzseInconsistency(thirtyDaysAgo);
            items.add(DataCheckItem.builder()
                    .name("sse_szse_consistency")
                    .displayName("沪深交易日一致性检测")
                    .passed(inconsistency == 0)
                    .level(CheckLevel.WARN)
                    .message(inconsistency == 0 ? "通过，最近 30 天沪深交易日一致"
                            : "最近 30 天沪深交易日不一致 " + inconsistency + " 天")
                    .build());

            // Check 3: weekend marked as trading day (ERROR)
            // A 股从未在周六/周日开市（即使法定调休补班日也不开市），周末 is_open=1 属于明确数据故障。
            List<TradeCalDO> openDays = tradeCalMapper.selectList(
                    new LambdaQueryWrapper<TradeCalDO>().eq(TradeCalDO::getIsOpen, TradeDayStatusEnum.OPEN));
            int weekendCount = 0;
            for (TradeCalDO day : openDays) {
                LocalDate d = parseCalDate(day.getCalDate());
                if (d != null) {
                    DayOfWeek dow = d.getDayOfWeek();
                    if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                        weekendCount++;
                    }
                }
            }
            items.add(DataCheckItem.builder()
                    .name("weekend_trading")
                    .displayName("周末交易日检测")
                    .passed(weekendCount == 0)
                    .level(CheckLevel.ERROR)
                    .message(weekendCount == 0 ? "通过，无周末被标记为交易日" : "存在 " + weekendCount + " 个周末被标记为交易日")
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.TRADE_CAL.getLabel())
                    .totalRows(totalRows)
                    .latestDate(null)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for trade_cal", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + SensitiveDataUtil.mask(e.getMessage()))
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.TRADE_CAL.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}