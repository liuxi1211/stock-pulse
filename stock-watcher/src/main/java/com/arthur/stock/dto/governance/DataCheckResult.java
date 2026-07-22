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
public class DataCheckResult {
    private String tableCode;
    private String tableName;
    private long totalRows;           // 总记录数
    private String latestDate;        // 最新数据日期（yyyyMMdd 或 null）
    private List<DataCheckItem> items;  // 所有检测项结果（含通过和不通过）
}
