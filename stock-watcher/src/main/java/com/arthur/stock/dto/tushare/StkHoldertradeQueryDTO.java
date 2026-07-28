package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StkHoldertradeQueryDTO {

    private String tsCode;
    private String annDate;
    private String startDate;
    private String endDate;
}
