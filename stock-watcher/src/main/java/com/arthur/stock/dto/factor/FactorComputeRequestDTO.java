package com.arthur.stock.dto.factor;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 单标的多因子计算请求（透传 engine 的 FactorComputeRequest）。
 * <p>
 * 注意：{@code data} 是 OHLCV 记录列表，每条记录为 date/open/high/low/close/volume 等键值，
 * 结构由 Python engine 的 Pydantic 模型定义，属于「跨系统透传」例外（见 api-design §11.3-1），
 * 故保留 {@code List<Map<String,Object>>}，不强行建模为静态字段。
 */
@Data
@Schema(description = "单标的多因子计算请求")
public class FactorComputeRequestDTO {

    @Schema(description = "OHLCV 记录列表（含 date/open/high/low/close/volume）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Map<String, Object>> data;

    @NotEmpty(message = "factors 不能为空")
    @Schema(description = "要计算的因子规格列表，至少 1 个", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<FactorComputeSpecDTO> factors;
}
