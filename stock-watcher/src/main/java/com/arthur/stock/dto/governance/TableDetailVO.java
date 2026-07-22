package com.arthur.stock.dto.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableDetailVO {
    private String tableCode;
    private String tableName;
    private String tableGroup;
    private String tushareApi;
    private Long totalRows;
    private String latestDate;
    private String earliestDate;
    private String updateFrequency;
    private String expectedUpdateTime;
    private boolean isDaily;
    private List<DataCheckItem> checkItems;
    private String lastCheckTime;
    private String status;
}
