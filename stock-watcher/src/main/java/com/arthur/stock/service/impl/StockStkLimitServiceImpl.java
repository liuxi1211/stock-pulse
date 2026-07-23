package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.StkLimitDTO;
import com.arthur.stock.dto.tushare.StkLimitQueryDTO;
import com.arthur.stock.mapper.StockStkLimitMapper;
import com.arthur.stock.model.StockStkLimitDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.StockStkLimitService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 涨跌停价服务实现。
 * <p>
 * 数据源：tushare stk_limit（doc_id=183），接口单次不限行数，但分页更稳。
 * 落库策略：按业务键 (ts_code, trade_date) 单条 delete-then-insert，保证幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockStkLimitServiceImpl implements StockStkLimitService, DataCheckable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** stk_limit 分页大小（接口不限行数，分页更稳） */
    private static final int PAGE_SIZE = 10000;

    /** 批量写入批次大小 */
    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final StockStkLimitMapper stockStkLimitMapper;

    @Override
    public int fetchAndSaveAll() {
        log.info("Fetching stock_stk_limit (paginated, size={})", PAGE_SIZE);
        List<StkLimitDTO> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<StkLimitDTO> page = tushareClient.stkLimit(
                    StkLimitQueryDTO.builder().build(), offset, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(page);
            log.info("stock_stk_limit page fetched: offset={}, size={}, total={}", offset, page.size(), all.size());
            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
        int total = persistByBizKey(all);
        log.info("Saved {} stock_stk_limit records", total);
        return total;
    }

    @Override
    public int fetchAndSaveIncremental(String tradeDate) {
        log.info("Fetching stock_stk_limit incremental for tradeDate={}", tradeDate);
        List<StkLimitDTO> rows = tushareClient.stkLimit(
                StkLimitQueryDTO.builder().startDate(tradeDate).endDate(tradeDate).build(), null, null);
        int total = persistByBizKey(rows);
        log.info("Saved {} incremental stock_stk_limit records for {}", total, tradeDate);
        return total;
    }

    @Override
    public Map<String, Map<String, StockStkLimitDO>> listByRange(List<String> tsCodes, String startDate, String endDate) {
        if (tsCodes == null || tsCodes.isEmpty() || startDate == null || endDate == null) {
            return Collections.emptyMap();
        }
        List<StockStkLimitDO> rows = stockStkLimitMapper.selectByTsCodesAndRange(tsCodes, startDate, endDate);
        Map<String, Map<String, StockStkLimitDO>> result = new LinkedHashMap<>();
        for (StockStkLimitDO row : rows) {
            if (row.getTsCode() == null || row.getTradeDate() == null) {
                continue;
            }
            result.computeIfAbsent(row.getTsCode(), k -> new LinkedHashMap<>())
                    .put(row.getTradeDate(), row);
        }
        return result;
    }

    // ==================== 内部方法 ====================

    /**
     * 按业务键 (ts_code, trade_date) 批量先删后插，保证幂等。
     */
    private int persistByBizKey(List<StkLimitDTO> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<StockStkLimitDO> entities = rows.stream()
                .map(this::toEntity)
                .filter(e -> e != null)
                .collect(Collectors.toList());
        int count = 0;
        for (List<StockStkLimitDO> batch : Lists.partition(entities, BATCH_SIZE)) {
            stockStkLimitMapper.deleteBatchByKeys(batch);
            count += stockStkLimitMapper.insertBatch(batch);
        }
        return count;
    }

    private StockStkLimitDO toEntity(StkLimitDTO dto) {
        if (dto == null || dto.getTsCode() == null || dto.getTradeDate() == null) {
            return null;
        }
        return StockStkLimitDO.builder()
                .tsCode(dto.getTsCode())
                .tradeDate(dto.getTradeDate())
                .preClose(dto.getPreClose())
                .upLimit(dto.getUpLimit())
                .downLimit(dto.getDownLimit())
                .build();
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.STK_LIMIT.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = stockStkLimitMapper.selectCount(null);
            String latestDate = stockStkLimitMapper.selectLatestTradeDate();
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
            boolean pricePassed;
            String priceMsg;
            if (totalRows == 0) {
                pricePassed = true;
                priceMsg = "表为空，跳过检测";
            } else {
                int invalidCount = stockStkLimitMapper.countPriceLogicErrors(thirtyDaysAgo);
                pricePassed = invalidCount == 0;
                priceMsg = pricePassed ? "通过，最近 30 天涨跌停价逻辑正常" : "最近 30 天涨跌停价逻辑异常 " + invalidCount + " 条";
            }
            items.add(DataCheckItem.builder()
                    .name("price_logic")
                    .displayName("涨跌停价逻辑检测")
                    .passed(pricePassed)
                    .level(CheckLevel.ERROR)
                    .message(priceMsg)
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.STK_LIMIT.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for stk_limit", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.STK_LIMIT.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
