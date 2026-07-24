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
import com.arthur.stock.service.StockSuspendDService;
import com.arthur.stock.service.TradeCalService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 落库策略：按业务键 (ts_code, trade_date) 单条 delete-then-insert，保证幂等。
 * 停牌日期推导：基于 S/R 事件序列，用状态机计算每日实际停牌状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockSuspendDServiceImpl implements StockSuspendDService, DataCheckable {

    private static final int PAGE_SIZE = 5000;
    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TushareClient tushareClient;
    private final StockSuspendDMapper stockSuspendDMapper;
    private final TradeCalService tradeCalService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveAll() {
        log.info("Fetching stock_suspend_d (paginated, size={})", PAGE_SIZE);
        List<SuspendDDTO> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<SuspendDDTO> page = tushareClient.suspendD(
                    SuspendDQueryDTO.builder().build(), offset, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(page);
            log.info("stock_suspend_d page fetched: offset={}, size={}, total={}", offset, page.size(), all.size());
            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
        int total = persistByBizKey(all);
        log.info("Saved {} stock_suspend_d records", total);
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
        List<StockSuspendDDO> entities = rows.stream()
                .map(this::toEntity)
                .filter(e -> e != null)
                .collect(Collectors.toList());
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
                                : "事件序列异常股票 " + badSeqCount + " 只（连续 S 无 R 或首事件为 R）")
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

    private int countBadSequenceStocks() {
        List<StockSuspendDDO> allEvents = stockSuspendDMapper.selectAllEventsOrderByCodeAndDate();
        if (allEvents.isEmpty()) {
            return 0;
        }
        Set<String> badStocks = new LinkedHashSet<>();
        String prevCode = null;
        String prevType = null;
        for (StockSuspendDDO e : allEvents) {
            if (!isFullDayEvent(e)) {
                continue;
            }
            String code = e.getTsCode();
            String type = e.getSuspendType();
            if (code == null || type == null) {
                continue;
            }
            if (!code.equals(prevCode)) {
                if ("R".equals(type)) {
                    badStocks.add(code);
                }
                prevCode = code;
                prevType = type;
            } else {
                if ("S".equals(type) && "S".equals(prevType)) {
                    badStocks.add(code);
                }
                prevType = type;
            }
        }
        return badStocks.size();
    }
}
