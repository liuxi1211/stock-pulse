package com.arthur.stock.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.constant.TushareApiEnum;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.NamechangeDTO;
import com.arthur.stock.dto.tushare.NamechangeQueryDTO;
import com.arthur.stock.mapper.StockNamechangeMapper;
import com.arthur.stock.model.StockNamechangeDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.StockNamechangeService;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 股票更名历史服务实现。
 * <p>
 * 数据源：tushare namechange（doc_id=160），单次最大 5000 行（分页）。
 * 落库策略：全量与增量均按业务键 (ts_code, start_date) 先删后插，幂等，支持逐页流式调用。
 * （流式逐页场景下不能按 ts_code 全删，否则会删掉前页已存记录，详见 persistByBizKey 注释。）
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
    private final TransactionTemplate transactionTemplate;

    @Override
    public int fetchAndSaveAll() {
        log.info("Fetching stock_namechange full (paginated, size={})", PAGE_SIZE);
        return fetchAndSavePagesStreaming(NamechangeQueryDTO.builder().build());
    }

    @Override
    public int fetchAndSaveByRange(String startDate, String endDate) {
        log.info("Fetching stock_namechange by range: {}~{}", startDate, endDate);
        return fetchAndSavePagesStreaming(NamechangeQueryDTO.builder()
                .startDate(startDate)
                .endDate(endDate)
                .build());
    }

    /**
     * 流式分页拉取并落库：拉一页存一页，每页一个独立事务，避免全量累积到内存。
     * <p>
     * 更名是稀疏事件，按日期区间 + 分页一次性拉取，仅按实际数据量发起少量分页请求，
     * 替代按交易日逐日拉取（会产生大量空请求）。
     * <p>
     * 注意：全量与区间均用 persistByBizKey（按业务键删插），不能用 persistByTsCode
     * （按 ts_code 全删），否则逐页流式会删掉前页已存的记录。
     *
     * @param baseParam 查询参数（tsCode/startDate/endDate 均可选，null 字段不传给 Tushare）
     * @return 落库记录数
     */
    private int fetchAndSavePagesStreaming(NamechangeQueryDTO baseParam) {
        // 构造查询参数：仅设非空字段（ts_code/start_date/end_date），与 TushareClient.namechange 口径一致。
        JSONObject params = new JSONObject();
        if (baseParam.getTsCode() != null) {
            params.put("ts_code", baseParam.getTsCode());
        }
        if (baseParam.getStartDate() != null) {
            params.put("start_date", baseParam.getStartDate());
        }
        if (baseParam.getEndDate() != null) {
            params.put("end_date", baseParam.getEndDate());
        }

        // 累计实际落库条数（persistByBizKey 会过滤掉 ts_code/start_date 为空的脏数据，
        // 与原 while 循环语义一致，故返回 saved 而非 fetched）。
        final int[] savedTotal = {0};
        tushareClient.queryWithPaging(
                TushareApiEnum.NAMECHANGE,
                params,
                NamechangeDTO.class,
                PAGE_SIZE,
                page -> {
                    // 流式落库：拉一页存一页，每页一个独立事务，避免全量累积到内存。
                    int saved = transactionTemplate.execute(status -> persistByBizKey(page));
                    savedTotal[0] += saved;
                    log.info("stock_namechange page saved: size={}, saved={}, total={}",
                            page.size(), saved, savedTotal[0]);
                });
        log.info("Saved {} stock_namechange records", savedTotal[0]);
        return savedTotal[0];
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
     * 落库：按业务键 (ts_code, start_date) 批量先删后插，幂等，支持逐页流式调用。
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
