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
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
 * 数据源：tushare stk_limit（doc_id=183）。
 * 落库策略：按日期范围（start_date ~ end_date）查询，内部 offset/limit 分页（每页 {@value #PAGE_SIZE}），
 * 每查到一页立即用一个独立短事务落库（事务内按 {@value #BATCH_SIZE} 批次 upsert），保证幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockStkLimitServiceImpl implements StockStkLimitService, DataCheckable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** stk_limit 分页大小（建议 ≤ 5000，避免接口截断） */
    private static final int PAGE_SIZE = 5000;

    /** 分页安全上限 */
    private static final int MAX_PAGES_PER_QUERY = 100;

    /** 批量写入批次大小 */
    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final StockStkLimitMapper stockStkLimitMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public int fetchAndSaveByRange(String startDate, String endDate) {
        log.info("Fetching stock_stk_limit for {}~{}", startDate, endDate);
        int total = 0;
        int offset = 0;
        int pageNum = 0;
        while (true) {
            pageNum++;
            if (pageNum > MAX_PAGES_PER_QUERY) {
                log.warn("[stock_stk_limit] reached max pages ({}), stopping early. total saved={}",
                        MAX_PAGES_PER_QUERY, total);
                break;
            }
            List<StkLimitDTO> page = tushareClient.stkLimit(
                    StkLimitQueryDTO.builder().startDate(startDate).endDate(endDate).build(),
                    offset, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            // 每批次独立短事务（500 行/事务），避免单事务持有连接过久触发 HikariCP 泄漏告警。
            // upsert 语义保证幂等：即使中途失败，下次重跑不会产生重复数据。
            int saved = persistByBatches(page);
            total += saved;
            log.info("stock_stk_limit page saved: range={}~{}, offset={}, size={}, saved={}, total={}",
                    startDate, endDate, offset, page.size(), saved, total);
            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
        log.info("Saved {} stock_stk_limit records for {}~{}", total, startDate, endDate);
        return total;
    }

    @Override
    public int fetchAndSaveIncremental() {
        String maxDate = stockStkLimitMapper.selectLatestTradeDate();
        String today = LocalDate.now().format(DATE_FMT);
        if (maxDate != null && maxDate.compareTo(today) >= 0) {
            log.info("stock_stk_limit is up to date (maxDate={})", maxDate);
            return 0;
        }
        String startDate = maxDate != null ? maxDate : today;
        log.info("stock_stk_limit incremental: {}~{}", startDate, today);
        return fetchAndSaveByRange(startDate, today);
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
     * 按业务键 (ts_code, trade_date) 批量幂等写入，利用主键冲突 ON DUPLICATE KEY UPDATE。
     * <p>
     * 每个批次（{@value #BATCH_SIZE} 行）独立事务提交，连接持有时间控制在秒级，
     * 避免 HikariCP 连接泄漏告警（leak-detection-threshold=60s）。
     * upsert 语义保证幂等：即使中途某批次失败，下次重跑不会产生重复数据。
     */
    private int persistByBatches(List<StkLimitDTO> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<StockStkLimitDO> entities = rows.stream()
                .map(this::toEntity)
                .filter(e -> e != null)
                .collect(Collectors.toList());
        int count = 0;
        for (List<StockStkLimitDO> batch : Lists.partition(entities, BATCH_SIZE)) {
            count += transactionTemplate.execute(status -> stockStkLimitMapper.upsertBatch(batch));
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
