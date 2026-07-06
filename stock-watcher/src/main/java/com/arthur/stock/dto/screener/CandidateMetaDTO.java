package com.arthur.stock.dto.screener;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 选股候选股元信息（meta），透传 engine snapshot 的 candidates.*.meta。
 * <p>
 * engine filters.py 用 snake_case 的 key 读取（is_st/is_suspended/is_limit_up/is_limit_down/list_date），
 * 故多词字段用 {@link JSONField} 显式映射。{@code industry} 为单词无需映射。
 * <p>
 * {@code listDate} 为 {@code YYYY-MM-DD} 格式（engine 期望），stock_basic.list_date 存储为 YYYYMMDD，service 层需做转换。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "选股候选股元信息")
public class CandidateMetaDTO {

    @JSONField(name = "is_st")
    @Schema(description = "是否 ST/*ST 股")
    private Boolean isSt;

    @JSONField(name = "is_suspended")
    @Schema(description = "是否停牌")
    private Boolean isSuspended;

    @JSONField(name = "is_limit_up")
    @Schema(description = "是否涨停")
    private Boolean isLimitUp;

    @JSONField(name = "is_limit_down")
    @Schema(description = "是否跌停")
    private Boolean isLimitDown;

    @Schema(description = "所属行业")
    private String industry;

    @JSONField(name = "list_date")
    @Schema(description = "上市日期，格式 YYYY-MM-DD")
    private String listDate;
}
