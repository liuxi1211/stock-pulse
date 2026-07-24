package com.arthur.stock.dto.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskProgressVO {
    private String taskId;
    private String tableCode;
    private String status;
    private String currentStep;
    private String errorMessage;
    private boolean cancelled;
    private String lastUpdated;
    private Integer currentIndex;
    private Integer totalCount;
}
