package com.arthur.stock.dto.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableStatusVO {
    private String tableCode;
    private String tableName;
    private String tableGroup;
    private Long totalRows;
    private BigDecimal rowDeltaPct;
    private String latestDate;
    private String status;
    private int failedCount;
    private List<DataCheckItem> checkItems;
    private String lastCheckTime;
    private String lastUpdateTime;
    private String updateFrequency;

    /** 关联定时任务 cron（多任务取第一个，无关联任务为 null，向后兼容） */
    private String cron;

    /** 关联定时任务下一帧执行时间（无关联任务为 null） */
    private String nextExecutionTime;

    /** 关联定时任务最近一次执行时间（无关联任务为 null） */
    private String lastExecutionTime;
}
