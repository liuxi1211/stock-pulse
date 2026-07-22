package com.arthur.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "自选股价格提醒设置请求")
public class WatchlistReminderRequest {

    @Schema(description = "目标价上限（高于此价触发提醒）")
    private BigDecimal targetPriceHigh;

    @Schema(description = "目标价下限（低于此价触发提醒）")
    private BigDecimal targetPriceLow;
}
