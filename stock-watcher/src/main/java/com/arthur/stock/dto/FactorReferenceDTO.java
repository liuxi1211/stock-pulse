package com.arthur.stock.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 单个因子引用：factorKey + params。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorReferenceDTO {

    @NotBlank(message = "factor 不能为空")
    private String factor;

    @Builder.Default
    private Map<String, Object> params = new java.util.HashMap<>();

    /** 仅用于调用方标识关心的输出列；服务端始终返回全部输出列。 */
    private Integer outputIndex;
}
