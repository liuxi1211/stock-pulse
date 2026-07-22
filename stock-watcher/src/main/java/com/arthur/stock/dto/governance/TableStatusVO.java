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
}
