package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.NamechangeDTO;
import com.arthur.stock.dto.tushare.NamechangeQueryDTO;
import com.arthur.stock.mapper.StockNamechangeMapper;
import com.arthur.stock.model.StockNamechangeDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.StockNamechangeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
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
public class StockNamechangeServiceImpl implements StockNamechangeService, DataCheckable {

    /** namechange 单次分页大小（Tushare 上限 5000） */
    private static final int PAGE_SIZE = 5000;

    /** 批量写入批次大小 */
    private static final int BATCH_SIZE = 500;

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
        // 按 ts_code 批量删除（单条 IN 语句）
        stockNamechangeMapper.delete(new LambdaQueryWrapper<StockNamechangeDO>()
                .in(StockNamechangeDO::getTsCode, codes));

        List<StockNamechangeDO> entities = rows.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        int count = 0;
        for (List<StockNamechangeDO> batch : Lists.partition(entities, BATCH_SIZE)) {
            count += stockNamechangeMapper.insertBatch(batch);
        }
        return count;
    }

    /**
     * 增量落库：按业务键 (ts_code, start_date) 批量先删后插，避免误删历史。
     */
    private int persistByBizKey(List<NamechangeDTO> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<StockNamechangeDO> entities = rows.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        int count = 0;
        for (List<StockNamechangeDO> batch : Lists.partition(entities, BATCH_SIZE)) {
            stockNamechangeMapper.deleteBatchByKeys(batch);
            count += stockNamechangeMapper.insertBatch(batch);
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

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.NAMECHANGE.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = stockNamechangeMapper.selectCount(null);
            String latestDate = stockNamechangeMapper.selectMaxStartDate();

            if (totalRows == 0) {
                items.add(DataCheckItem.builder()
                        .name("date_logic")
                        .displayName("日期逻辑检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("date_overlap")
                        .displayName("日期重叠检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("name_validity")
                        .displayName("名称有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
            } else {
                int dateLogicErrors = stockNamechangeMapper.countDateLogicErrors();
                items.add(DataCheckItem.builder()
                        .name("date_logic")
                        .displayName("日期逻辑检测")
                        .passed(dateLogicErrors == 0)
                        .level(CheckLevel.ERROR)
                        .message(dateLogicErrors == 0 ? "通过，日期逻辑正常"
                                : "起始日期大于结束日期的记录 " + dateLogicErrors + " 条")
                        .build());

                int dateOverlap = stockNamechangeMapper.countDateOverlap();
                items.add(DataCheckItem.builder()
                        .name("date_overlap")
                        .displayName("日期重叠检测")
                        .passed(dateOverlap == 0)
                        .level(CheckLevel.WARN)
                        .message(dateOverlap == 0 ? "通过，无日期重叠"
                                : "同一股票相邻记录日期重叠 " + dateOverlap + " 条")
                        .build());

                int invalidName = stockNamechangeMapper.countInvalidName();
                items.add(DataCheckItem.builder()
                        .name("name_validity")
                        .displayName("名称有效性检测")
                        .passed(invalidName == 0)
                        .level(CheckLevel.ERROR)
                        .message(invalidName == 0 ? "通过，名称数据正常"
                                : "名称为空的记录 " + invalidName + " 条")
                        .build());
            }

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.NAMECHANGE.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for namechange", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.NAMECHANGE.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
