package com.arthur.stock.dto.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasourceVO {
    private String sourceCode;
    private String sourceName;
    private String status;
    private String lastTestTime;
    private boolean lastTestOk;
    private long responseTimeMs;
    private String testInterface;
}
