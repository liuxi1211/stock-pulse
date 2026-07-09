package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 策略版本回滚请求。
 * <p>
 * 内部复用 {@link #updateStrategyConfig} 链路：以目标版本 config 为新版本写入，
 * 会再次走 engine 校验、乐观锁与状态机。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "策略版本回滚请求")
public class StrategyRollbackRequest {

    @Schema(description = "目标版本号（必须已存在）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer targetVersion;

    @Schema(description = "回滚变更说明（自动拼接 '回滚到 vX' 前缀）")
    private String changelog;
}
