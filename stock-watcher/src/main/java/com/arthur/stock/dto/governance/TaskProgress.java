package com.arthur.stock.dto.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskProgress {
    private String taskId;
    private String tableCode;
    private String status;         // RUNNING / SUCCESS / FAILED / CANCELLED
    private String currentStep;    // 当前步骤描述
    private String errorMessage;   // 失败时的错误信息
    private boolean cancelled;     // 是否已取消
    private String lastUpdated;    // 最后更新时间 yyyy-MM-dd HH:mm:ss
}
