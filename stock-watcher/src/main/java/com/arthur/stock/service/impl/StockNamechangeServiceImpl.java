package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.NamechangeDTO;
import com.arthur.stock.dto.tushare.NamechangeQueryDTO;
import com.arthur.stock.mapper.StockNamechangeMapper;
import com.arthur.stock.model.StockNamechangeDO;
import com.arthur.stock.service.StockNamechangeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 股票更名历史服务实现。
 * <p>
 * 数据源：tushare namechange（doc_id=160），单次最大 5000 行（分页）。
 * 落库策略：
 * <ul>
 *   <li>全量：接口返回某 ts_code 的全部历史，按 ts_code 先删后插（全量替换，幂等）；</li>
 *   <li>增量：按业务键 (ts_code, start_date) 单条先删后插，避免误删历史记录。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockNamechangeServiceImpl implements StockNamechangeService {

    /** namechange 单次分页大小（Tushare 上限 5000） */
    private static final int PAGE_SIZE = 5000;

    private final TushareClient tushareClient;
    private final StockNamechangeMapper stockNamechangeMapper;

    @Override
    public int fetchAndSaveAll() {
        log.info("Fetching stock_namechange (paginated, size={})", PAGE_SIZE);
        List<NamechangeDTO> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<NamechangeDTO> page = tushareClient.namechange(
                    NamechangeQueryDTO.builder().build(), offset, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(page);
            log.info("stock_namechange page fetched: offset={}, size={}, total={}", offset, page.size(), all.size());
            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
        int total = persistByTsCode(all);
        log.info("Saved {} stock_namechange records", total);
        return total;
    }

    @Override
    public int fetchAndSaveIncremental(String tradeDate) {
        log.info("Fetching stock_namechange incremental for tradeDate={}", tradeDate);
        // namechange 按 start_date 增量：拉取 startDate=tradeDate 的记录
        List<NamechangeDTO> rows = tushareClient.namechange(
                NamechangeQueryDTO.builder().startDate(tradeDate).endDate(tradeDate).build(), null, null);
        int total = persistByBizKey(rows);
        log.info("Saved {} incremental stock_namechange records for {}", total, tradeDate);
        return total;
    }

    @Override
    public Map<String, List<StockNamechangeDO>> listByTsCodes(List<String> tsCodes) {
        if (tsCodes == null || tsCodes.isEmpty()) {
            return Collections.emptyMap();
        }
        List<StockNamechangeDO> rows = stockNamechangeMapper.selectByTsCodes(tsCodes);
        return rows.stream().collect(Collectors.groupingBy(StockNamechangeDO::getTsCode));
    }

    // ==================== 内部方法 ====================

    /**
     * 全量落库：按 ts_code 先删后插（接口返回某 ts_code 的全部历史，全量替换等价幂等）。
     */
    private int persistByTsCode(List<NamechangeDTO> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        Set<String> codes = rows.stream()
                .map(NamechangeDTO::getTsCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (String code : codes) {
            stockNamechangeMapper.delete(new LambdaQueryWrapper<StockNamechangeDO>()
                    .eq(StockNamechangeDO::getTsCode, code));
        }
        int count = 0;
        for (NamechangeDTO dto : rows) {
            StockNamechangeDO entity = toEntity(dto);
            if (entity == null) {
                continue;
            }
            stockNamechangeMapper.insert(entity);
            count++;
        }
        return count;
    }

    /**
     * 增量落库：按业务键 (ts_code, start_date) 单条先删后插，避免误删历史。
     */
    private int persistByBizKey(List<NamechangeDTO> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (NamechangeDTO dto : rows) {
            StockNamechangeDO entity = toEntity(dto);
            if (entity == null) {
                continue;
            }
            stockNamechangeMapper.delete(new LambdaQueryWrapper<StockNamechangeDO>()
                    .eq(StockNamechangeDO::getTsCode, entity.getTsCode())
                    .eq(StockNamechangeDO::getStartDate, entity.getStartDate()));
            stockNamechangeMapper.insert(entity);
            count++;
        }
        return count;
    }

    private StockNamechangeDO toEntity(NamechangeDTO dto) {
        if (dto == null || dto.getTsCode() == null || dto.getStartDate() == null) {
            return null;
        }
        return StockNamechangeDO.builder()
                .tsCode(dto.getTsCode())
                .name(dto.getName())
                .startDate(dto.getStartDate())
                .endDate(dto.getEndDate())
                .changeReason(dto.getChangeReason())
                .build();
    }
}
