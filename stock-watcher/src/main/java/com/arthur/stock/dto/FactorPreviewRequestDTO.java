package com.arthur.stock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 前端调用 /api/factors/preview 的请求体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorPreviewRequestDTO {

    @NotBlank(message = "tsCode 不能为空")
    private String tsCode;

    private String startDate;

    private String endDate;

    /** 默认取最近 120 条 OHLCV */
    @Builder.Default
    private int size = 120;

    @NotEmpty(message = "factors 数组不能为空")
    @Size(min = 1, message = "至少请求一个因子")
    private List<FactorReferenceDTO> factors;
}
