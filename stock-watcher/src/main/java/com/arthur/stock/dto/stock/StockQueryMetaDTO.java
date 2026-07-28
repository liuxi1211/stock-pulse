package com.arthur.stock.dto.stock;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StockQueryMetaDTO {

    private String symbol;
    private String dataAsOf;
    private String period;
    private String adjustment;
    private String startDate;
    private String endDate;
    private Integer limit;
    private Integer page;
    private Integer size;
    private Integer total;
}
