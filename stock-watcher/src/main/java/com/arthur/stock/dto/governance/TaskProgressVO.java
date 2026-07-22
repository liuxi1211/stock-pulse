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
    private int progressPct;
    private String currentStep;
    private long processedItems;
    private long totalItems;
    private boolean cancelled;
    private String lastUpdated;
}
