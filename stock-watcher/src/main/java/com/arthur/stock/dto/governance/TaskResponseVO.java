package com.arthur.stock.dto.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskResponseVO {
    private String taskId;
    private String tableCode;
    private String operationType;
    private String status;
}
