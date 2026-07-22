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
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final TradeCalMapper tradeCalMapper;

    /**
     * 从Tushare获取交易日历数据并保存到本地数据库，已存在的记录会更新。
     * <p>
     * 整个流程（拉取→删除旧记录→插入新记录→预计算调仓标记）在一个事务内，
     * 任何一步失败都会回滚，保证数据一致性。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<TradeCalDTO> fetchAndSaveTradeCal(String exchange, String startDate, String endDate) {
        log.info("Fetching trade_cal from Tushare: exchange={}, startDate={}, endDate={}", exchange, startDate, endDate);

        TradeCalQueryDTO param = TradeCalQueryDTO.builder()
                .exchange(exchange)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<TradeCalDTO> calendars = tushareClient.tradeCal(param);

        if (calendars.isEmpty()) {
            log.info("No trade_cal data returned");
            return Collections.emptyList();
        }

        List<TradeCalDO> entities = calendars.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TradeCalDO::getCalDate))
                .collect(Collectors.toList());

        saveCalendars(entities);
        log.info("Saved {} trade_cal records", entities.size());

        // 同步后预计算 6 个调仓标记（周/月/季的 first/last），供 engine 调仓日判定使用。
        computeAndSaveRebalanceFlags();
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
     * - 删除用 {@code exchange = ? AND cal_date IN (...)}（单字段 IN，MySQL/SQLite 双方言兼容）
     * - 插入用多值 INSERT（双方言通用）
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

            Lists.partition(exCalendars, BATCH_SIZE).forEach(batch -> {
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
     * 且单条 SQL 跨 MySQL/SQLite 方言通用）。
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
            for (List<TradeCalDO> batch : Lists.partition(toUpdate, BATCH_SIZE)) {
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

            // Check 1: Calendar doesn't cover next 30 days (ERROR)
            TradeCalDO latest = tradeCalMapper.selectOne(
                    new LambdaQueryWrapper<TradeCalDO>()
                            .select(TradeCalDO::getCalDate)
                            .orderByDesc(TradeCalDO::getCalDate)
                            .last("LIMIT 1"));
            String maxCalDate = latest != null ? latest.getCalDate() : null;
            String todayPlus30 = today.plusDays(30).format(CAL_DATE_FMT);
            boolean coveragePassed = maxCalDate != null && maxCalDate.compareTo(todayPlus30) >= 0;
            items.add(DataCheckItem.builder()
                    .name("future_coverage")
                    .displayName("未来覆盖度检测")
                    .passed(coveragePassed)
                    .level(CheckLevel.ERROR)
                    .message(coveragePassed ? "通过，日历覆盖至 " + maxCalDate
                            : "交易日历仅覆盖至 " + maxCalDate + "，不足未来 30 天（" + todayPlus30 + "）")
                    .build());

            // Check 2: Missing exchange (ERROR)
            long sseCount = tradeCalMapper.selectCount(
                    new LambdaQueryWrapper<TradeCalDO>().eq(TradeCalDO::getExchange, ExchangeEnum.SSE));
            long szseCount = tradeCalMapper.selectCount(
                    new LambdaQueryWrapper<TradeCalDO>().eq(TradeCalDO::getExchange, ExchangeEnum.SZSE));
            boolean exchangePassed = sseCount > 0 && szseCount > 0;
            items.add(DataCheckItem.builder()
                    .name("exchange_coverage")
                    .displayName("交易所覆盖检测")
                    .passed(exchangePassed)
                    .level(CheckLevel.ERROR)
                    .message(exchangePassed ? "通过，SSE " + sseCount + " 条，SZSE " + szseCount + " 条"
                            : "交易所覆盖缺失，SSE " + sseCount + " 条，SZSE " + szseCount + " 条")
                    .build());

            // Check 3: SSE/SZSE inconsistency last 30 days (WARN)
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

            // Check 4: Weekend marked as trading day (ERROR)
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