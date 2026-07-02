package com.arthur.stock.dto.factor;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 多标的批量因子计算请求（透传 engine 的 BatchFactorComputeRequest）。
 * <p>
 * {@code data} 为 {symbol: [OHLCV 记录]}，结构由 Python engine 定义，属于「跨系统透传」例外
 * （见 api-design §11.3-1）。
 */
@Data
@Schema(description = "多标的批量因子计算请求")
public class FactorBatchComputeRequestDTO {

    @Schema(description = "{symbol: [OHLCV 记录]} 多标的行情数据",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<String, List<Map<String, Object>>> data;

    @NotEmpty(message = "factors 不能为空")
    @Schema(description = "要计算的因子规格列表，至少 1 个", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<FactorComputeSpecDTO> factors;
}
