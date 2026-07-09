package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 策略状态变更请求。status 取值见 {@link com.arthur.stock.constant.StrategyStatusEnum}，
 * 流转合法性由 {@link com.arthur.stock.constant.StrategyStatusEnum#canTransitionTo} 校验。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "策略状态变更请求")
public class StrategyStatusUpdateRequest {

    @NotBlank(message = "目标状态不能为空")
    @Schema(description = "目标状态（DRAFT/VERIFIED/ACTIVE/ARCHIVED）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;
}
