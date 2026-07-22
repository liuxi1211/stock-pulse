package com.arthur.stock.dto.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PullLogVO {
    private Long id;
    private String taskId;
    private String tableCode;
    private String tableName;
    private String operationType;
    private String status;
    private String startTime;
    private String endTime;
    private Long durationMs;
    private Long totalCount;
    private Long successCount;
    private Long failCount;
    private String errorMessage;
    private String errorStack;
    private String operator;
}
