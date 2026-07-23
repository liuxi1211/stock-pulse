package com.arthur.stock.service.impl;

import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.mapper.TopInstMapper;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.TopInstService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TopInstServiceImpl implements TopInstService, DataCheckable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TopInstMapper topInstMapper;

    @Override
    public String getTableCode() {
        return InitStep.TOP_INST.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = topInstMapper.selectCount(null);
            String latestDate = topInstMapper.selectMaxTradeDate();

            if (totalRows == 0) {
                items.add(DataCheckItem.builder()
                        .name("amount_validity")
                        .displayName("买卖额有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("side_amount_consistency")
                        .displayName("方向金额一致性检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("toplist_consistency")
                        .displayName("龙虎榜关联一致性检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
            } else {
                String thirtyDaysAgo = LocalDate.now().minusDays(30).format(DATE_FMT);

                int invalidAmount = topInstMapper.countInvalidAmount(thirtyDaysAgo);
                items.add(DataCheckItem.builder()
                        .name("amount_validity")
                        .displayName("买卖额有效性检测")
                        .passed(invalidAmount == 0)
                        .level(CheckLevel.ERROR)
                        .message(invalidAmount == 0 ? "通过，最近30天买卖额数据正常"
                                : "最近30天买卖额为负的记录 " + invalidAmount + " 条")
                        .build());

                int sideAmountInconsistency = topInstMapper.countSideAmountInconsistency(thirtyDaysAgo);
                items.add(DataCheckItem.builder()
                        .name("side_amount_consistency")
                        .displayName("方向金额一致性检测")
                        .passed(sideAmountInconsistency == 0)
                        .level(CheckLevel.WARN)
                        .message(sideAmountInconsistency == 0 ? "通过，最近30天方向与金额一致"
                                : "最近30天方向与金额矛盾的记录 " + sideAmountInconsistency + " 条")
                        .build());

                int missingInToplist = topInstMapper.countMissingInToplist(thirtyDaysAgo);
                items.add(DataCheckItem.builder()
                        .name("toplist_consistency")
                        .displayName("龙虎榜关联一致性检测")
                        .passed(missingInToplist == 0)
                        .level(CheckLevel.WARN)
                        .message(missingInToplist == 0 ? "通过，最近30天数据均关联龙虎榜"
                                : "最近30天在龙虎榜中不存在的记录 " + missingInToplist + " 条")
                        .build());
            }

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.TOP_INST.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for top_inst", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.TOP_INST.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
