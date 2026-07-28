package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.constant.TushareApiEnum;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.StkHoldertradeDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.StkHoldertradeMapper;
import com.arthur.stock.model.StkHoldertradeDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.StkHoldertradeService;
import com.arthur.stock.service.StockBasicService;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class StkHoldertradeServiceImpl implements StkHoldertradeService, DataCheckable {

    private static final int BATCH_SIZE = 500;
    private static final int PAGE_SIZE = 5000;

    private final TushareClient tushareClient;
    private final StkHoldertradeMapper stkHoldertradeMapper;
    private final StockBasicService stockBasicService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public int fetchAndSave(String tsCode, String startDate, String endDate) {
        JSONObject params = new JSONObject();
        if (tsCode != null) {
            params.put("ts_code", tsCode);
        }
        if (startDate != null) {
            params.put("start_date", startDate);
        }
        if (endDate != null) {
            params.put("end_date", endDate);
        }

        int[] savedTotal = {0};
        tushareClient.queryWithPaging(
                TushareApiEnum.STK_HOLDERTRADE,
                params,
                StkHoldertradeDTO.class,
                PAGE_SIZE,
                page -> {
                    int saved = transactionTemplate.execute(status -> persistByBizKey(page));
                    savedTotal[0] += saved;
                });
        return savedTotal[0];
    }

    @Override
    public int fetchAndSaveAll(String startDate, String endDate) {
        List<StockBasicDTO> stocks = stockBasicService.queryLocal(null, null, null, "L");
        if (stocks == null || stocks.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (StockBasicDTO stock : stocks) {
            try {
                total += fetchAndSave(stock.getTsCode(), startDate, endDate);
            } catch (Exception e) {
                log.warn("stk_holdertrade {} 拉取失败: {}", stock.getTsCode(), e.getMessage());
            }
        }
        return total;
    }

    @Override
    public List<StkHoldertradeDTO> queryByDateRange(String tsCode, String startDate, String endDate) {
        return stkHoldertradeMapper.selectByTsCodeAndDateRange(tsCode, startDate, endDate)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 每页落库：转 entity -> 去重 -> 分批 500 delete+insert。
     * 按业务键删插（不是按 ts_code 全删），确保逐页流式不会删掉前页已存的记录。
     */
    private int persistByBizKey(List<StkHoldertradeDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<StkHoldertradeDO> entities = deduplicate(rows.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .toList());
        if (entities.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (List<StkHoldertradeDO> batch : Lists.partition(entities, BATCH_SIZE)) {
            stkHoldertradeMapper.deleteBatchByKeys(batch);
            count += stkHoldertradeMapper.insertBatch(batch);
        }
        return count;
    }

    private List<StkHoldertradeDO> deduplicate(List<StkHoldertradeDO> rows) {
        Map<String, StkHoldertradeDO> unique = new LinkedHashMap<>();
        for (StkHoldertradeDO row : rows) {
            String key = String.join("|", row.getTsCode(), row.getAnnDate(), row.getHolderName(), row.getInDe(),
                    row.getBeginDate(), row.getCloseDate());
            unique.put(key, row);
        }
        return List.copyOf(unique.values());
    }

    private StkHoldertradeDO toEntity(StkHoldertradeDTO row) {
        if (row == null || isBlank(row.getTsCode()) || isBlank(row.getAnnDate())
                || isBlank(row.getHolderName()) || isBlank(row.getInDe())) {
            return null;
        }
        return StkHoldertradeDO.builder()
                .tsCode(row.getTsCode())
                .annDate(row.getAnnDate())
                .holderName(row.getHolderName())
                .holderType(row.getHolderType())
                .inDe(row.getInDe())
                .changeVol(row.getChangeVol())
                .changeRatio(row.getChangeRatio())
                .afterShare(row.getAfterShare())
                .afterRatio(row.getAfterRatio())
                .avgPrice(row.getAvgPrice())
                .totalShare(row.getTotalShare())
                .beginDate(normalize(row.getBeginDate()))
                .closeDate(normalize(row.getCloseDate()))
                .build();
    }

    private StkHoldertradeDTO toDTO(StkHoldertradeDO row) {
        return StkHoldertradeDTO.builder()
                .tsCode(row.getTsCode())
                .annDate(row.getAnnDate())
                .holderName(row.getHolderName())
                .holderType(row.getHolderType())
                .inDe(row.getInDe())
                .changeVol(row.getChangeVol())
                .changeRatio(row.getChangeRatio())
                .afterShare(row.getAfterShare())
                .afterRatio(row.getAfterRatio())
                .avgPrice(row.getAvgPrice())
                .totalShare(row.getTotalShare())
                .beginDate(row.getBeginDate())
                .closeDate(row.getCloseDate())
                .build();
    }

    private String normalize(String value) {
        return isBlank(value) ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.STK_HOLDERTRADE.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = stkHoldertradeMapper.selectCount(null);
            String latestDate = queryMaxAnnDate();

            if (totalRows == 0) {
                items.add(DataCheckItem.builder()
                        .name("row_validity")
                        .displayName("数据完整性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
            } else {
                int invalidChangeVol = stkHoldertradeMapper.countInvalidChangeVol();
                items.add(DataCheckItem.builder()
                        .name("change_vol_validity")
                        .displayName("变动数量有效性检测")
                        .passed(invalidChangeVol == 0)
                        .level(CheckLevel.WARN)
                        .message(invalidChangeVol == 0 ? "通过，变动数量数据正常"
                                : "变动数量为空或非正的记录 " + invalidChangeVol + " 条")
                        .build());

                int missingInDe = stkHoldertradeMapper.countMissingInDe();
                items.add(DataCheckItem.builder()
                        .name("in_de_validity")
                        .displayName("增减持方向检测")
                        .passed(missingInDe == 0)
                        .level(CheckLevel.ERROR)
                        .message(missingInDe == 0 ? "通过，增减持方向完整"
                                : "增减持方向为空的记录 " + missingInDe + " 条")
                        .build());
            }

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.STK_HOLDERTRADE.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for stk_holdertrade", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.STK_HOLDERTRADE.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }

    private String queryMaxAnnDate() {
        try {
            return stkHoldertradeMapper.selectMaxAnnDate();
        } catch (Exception e) {
            log.warn("Failed to query MAX(ann_date) for stk_holdertrade: {}", e.getMessage());
            return null;
        }
    }
}
