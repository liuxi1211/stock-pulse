package com.arthur.stock.dto.screener;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 选股候选股数据（candidates.<symbol>），透传 engine snapshot 的 candidates 元素。
 * <p>
 * 包含：OHLCV 历史、基本面、元信息。{@code ohlcvHistory} 用 {@link JSONField} 映射为 engine 期望的 {@code ohlcv_history}。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "选股候选股数据")
public class CandidateStockDTO {

    @JSONField(name = "ohlcv_history")
    @Schema(description = "OHLCV 历史序列（按时间升序，末根为选股日）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<OhlcvBarDTO> ohlcvHistory;

    @Schema(description = "基本面因子值（如 PE_TTM / ROE_TTM），键为大写蛇形因子名")
    private Map<String, BigDecimal> fundamentals;

    @Schema(description = "候选股元信息")
    private CandidateMetaDTO meta;
}
