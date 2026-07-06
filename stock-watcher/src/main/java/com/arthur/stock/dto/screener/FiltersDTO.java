package com.arthur.stock.dto.screener;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 选股过滤选项，透传 engine snapshot 的 filters 字段。
 * <p>
 * engine Pydantic 期望 snake_case，多词字段用 {@link JSONField} 显式映射（序��化经 fastjson2）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "选股过滤选项")
public class FiltersDTO {

    @JSONField(name = "exclude_st")
    @Schema(description = "是否剔除 ST/*ST")
    private Boolean excludeSt;

    @JSONField(name = "exclude_suspended")
    @Schema(description = "是否剔除停牌")
    private Boolean excludeSuspended;

    @JSONField(name = "exclude_limit_up")
    @Schema(description = "是否剔除涨停")
    private Boolean excludeLimitUp;

    @JSONField(name = "exclude_limit_down")
    @Schema(description = "是否剔除跌停")
    private Boolean excludeLimitDown;

    @Schema(description = "仅保留这些行业（白名单）")
    private List<String> industries;

    @JSONField(name = "exclude_industries")
    @Schema(description = "排除这些行业（黑名单）")
    private List<String> excludeIndustries;

    @JSONField(name = "min_list_days")
    @Schema(description = "最小上市天数")
    private Integer minListDays;
}
