package com.arthur.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "自选股批量操作请求")
public class WatchlistBatchRequest {

    @Schema(description = "股票代码列表", required = true)
    private List<String> stockCodes;

    @Schema(description = "分组ID（批量移动分组时使用，批量删除时不传）")
    private Long groupId;
}
