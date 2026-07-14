package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 策略对外展示 DTO（spec 004 Task 6）。
 * <p>
 * 对应主表 quant_strategy 的对外字段；{@code config} 为当前生效版本（current_version）的配置 JSON，
 * 仅在详情接口返回，列表接口默认不返回。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "策略对象")
public class StrategyDTO {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "策略业务标识（全局唯一）")
    private String strategyId;

    @Schema(description = "策略名称")
    private String name;

    @Schema(description = "策略描述")
    private String description;

    @Schema(description = "策略分类（TECHNICAL/FUNDAMENTAL/MIXED/CUSTOM）")
    private String category;

    @Schema(description = "适用范围（single/portfolio）")
    private String scope;

    @Schema(description = "状态（DRAFT/VERIFIED/ACTIVE/ARCHIVED）")
    private String status;

    @Schema(description = "标签列表")
    private List<String> tags;

    @Schema(description = "当前生效版本号")
    private Integer currentVersion;

    @Schema(description = "创建时间（UTC ISO8601）")
    private String createdAt;

    @Schema(description = "更新时间（UTC ISO8601）")
    private String updatedAt;

    @Schema(description = "最近一次回测累计收益率（%, 原始百分数如 34.7 表示 34.7%）")
    private Double lastReturnPct;

    @Schema(description = "最近一次回测夏普比率")
    private Double lastSharpe;

    @Schema(description = "最近一次回测最大回撤（%, 正数存储如 11.2 表示 11.2%）")
    private Double lastMaxDrawdownPct;

    @Schema(description = "最近回测时间（UTC ISO8601）")
    private String lastBacktestTime;

    @Schema(description = "校验错误数（0 表示无错误）")
    private Integer validationErrorCount;

    @Schema(description = "当前版本的配置 JSON（仅详情接口返回）")
    private String config;
}
