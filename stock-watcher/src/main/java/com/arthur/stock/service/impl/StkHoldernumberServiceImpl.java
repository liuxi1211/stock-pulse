package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.constant.TushareApiEnum;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.StkHoldernumberDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.mapper.StkHoldernumberMapper;
import com.arthur.stock.model.StkHoldernumberDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.StkHoldernumberService;
import com.arthur.stock.service.StockBasicService;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class StkHoldernumberServiceImpl implements StkHoldernumberService, DataCheckable {

    private static final int BATCH_SIZE = 500;
    private static final int PAGE_SIZE = 5000;

    private final TushareClient tushareClient;
    private final StkHoldernumberMapper stkHoldernumberMapper;
    private final StockBasicService stockBasicService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public int fetchAndSave(String tsCode) {
        JSONObject params = new JSONObject();
        if (tsCode != null) {
            params.put("ts_code", tsCode);
        }

        int[] savedTotal = {0};
        tushareClient.queryWithPaging(
                TushareApiEnum.STK_HOLDERNUMBER,
                params,
                StkHoldernumberDTO.class,
                PAGE_SIZE,
                page -> {
                    int saved = transactionTemplate.execute(status -> persistByBizKey(page));
                    savedTotal[0] += saved;
                });
        return savedTotal[0];
    }

    @Override
    public int fetchAndSaveAll() {
        List<StockBasicDTO> stocks = stockBasicService.queryLocal(null, null, null, "L");
        if (stocks == null || stocks.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (StockBasicDTO stock : stocks) {
            try {
                total += fetchAndSave(stock.getTsCode());
            } catch (Exception e) {
                log.warn("stk_holdernumber {} 拉取失败: {}", stock.getTsCode(), e.getMessage());
            }
        }
        return total;
    }

    @Override
    public List<StkHoldernumberDTO> queryRecent(String tsCode, int limit) {
        List<StkHoldernumberDO> rows = stkHoldernumberMapper.selectRecentByTsCode(tsCode, limit);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream().map(this::toDTO).toList();
    }

    /**
     * 每页落库：转 entity -> 分批 500 delete+insert。
     * 按业务键删插（不是按 ts_code 全删），确保逐页流式不会删掉前页已存的记录。
     */
    private int persistByBizKey(List<StkHoldernumberDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<StkHoldernumberDO> entities = rows.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .toList();
        if (entities.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (List<StkHoldernumberDO> batch : Lists.partition(entities, BATCH_SIZE)) {
            stkHoldernumberMapper.deleteBatchByKeys(batch);
            count += stkHoldernumberMapper.insertBatch(batch);
        }
        return count;
    }

    private StkHoldernumberDO toEntity(StkHoldernumberDTO row) {
        if (row == null || row.getTsCode() == null || row.getEndDate() == null) {
            return null;
        }
        return StkHoldernumberDO.builder()
                .tsCode(row.getTsCode())
                .annDate(row.getAnnDate())
                .endDate(row.getEndDate())
                .holderNum(row.getHolderNum())
                .build();
    }

    private StkHoldernumberDTO toDTO(StkHoldernumberDO row) {
        return StkHoldernumberDTO.builder()
                .tsCode(row.getTsCode())
                .annDate(row.getAnnDate())
                .endDate(row.getEndDate())
                .holderNum(row.getHolderNum())
                .build();
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.STK_HOLDERNUMBER.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = stkHoldernumberMapper.selectCount(null);
            String latestDate = stkHoldernumberMapper.selectMaxAnnDate();

            if (totalRows == 0) {
                items.add(DataCheckItem.builder()
                        .name("row_validity")
                        .displayName("数据完整性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
            } else {
                int invalidHolderNum = stkHoldernumberMapper.countInvalidHolderNum();
                items.add(DataCheckItem.builder()
                        .name("holder_num_validity")
                        .displayName("股东人数有效性检测")
                        .passed(invalidHolderNum == 0)
                        .level(CheckLevel.WARN)
                        .message(invalidHolderNum == 0 ? "通过，股东人数数据正常"
                                : "股东人数为空或非正的记录 " + invalidHolderNum + " 条")
                        .build());

                int missingEndDate = stkHoldernumberMapper.countMissingEndDate();
                items.add(DataCheckItem.builder()
                        .name("end_date_validity")
                        .displayName("截止日期检测")
                        .passed(missingEndDate == 0)
                        .level(CheckLevel.ERROR)
                        .message(missingEndDate == 0 ? "通过，截止日期完整"
                                : "截止日期为空的记录 " + missingEndDate + " 条")
                        .build());
            }

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.STK_HOLDERNUMBER.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for stk_holdernumber", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.STK_HOLDERNUMBER.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
