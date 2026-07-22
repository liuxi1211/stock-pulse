package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.SuspendDDTO;
import com.arthur.stock.dto.tushare.SuspendDQueryDTO;
import com.arthur.stock.mapper.StockSuspendDMapper;
import com.arthur.stock.model.StockSuspendDDO;
import com.arthur.stock.service.StockSuspendDService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 股票停复牌服务实现。
 * <p>
 * 数据源：tushare suspend_d（doc_id=161），单次最大 10000 行（分页）。
 * 落库策略：按业务键 (ts_code, trade_date) 单条 delete-then-insert，保证幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockSuspendDServiceImpl implements StockSuspendDService {

    /** suspend_d 单次分页大小（Tushare 上限 10000） */
    private static final int PAGE_SIZE = 10000;

    /** 批量写入批次大小 */
    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final StockSuspendDMapper stockSuspendDMapper;

    @Override
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
        List<StockSuspendDDO> rows = stockSuspendDMapper.selectByTsCodesAndRange(tsCodes, startDate, endDate);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (StockSuspendDDO row : rows) {
            if (row.getTsCode() == null || row.getTradeDate() == null) {
                continue;
            }
            result.computeIfAbsent(row.getTsCode(), k -> new LinkedHashSet<>()).add(row.getTradeDate());
        }
        return result;
    }

    // ==================== 内部方法 ====================

    /**
     * 按业务键 (ts_code, trade_date) 批量先删后插，保证幂等。
     */
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
        return StockSuspendDDO.builder()
                .tsCode(dto.getTsCode())
                .tradeDate(dto.getTradeDate())
                .suspReason(dto.getSuspReason())
                .resumpDate(dto.getResumpDate())
                .build();
    }
}
