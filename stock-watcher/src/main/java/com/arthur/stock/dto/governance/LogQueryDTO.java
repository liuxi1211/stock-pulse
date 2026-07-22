package com.arthur.stock.dto.governance;

import lombok.Data;

@Data
public class LogQueryDTO {
    private String tableCode;
    private String status;
    private String operationType;
    private String startDate;
    private String endDate;
    private Integer page = 1;
    private Integer size = 20;
}
