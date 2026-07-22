package com.arthur.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 全市场股票列表查询请求（行情中心 stock-list 页，spec Task 4）。
 * <p>
 * 共 7 个可选参数（参数数量 &gt; 5，按项目规范用 DTO 承载）。
 * rankType/sortBy/order 均为字符串而非枚举，因项目未注册 String-&gt;Enum 转换器，
 * 由 Service 层通过 {@code RankingType.fromCode} 等解析，避免 Spring MVC 按 name 绑定失败。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "全市场股票列表查询请求")
public class StockListQueryDTO {

    @Schema(description = "榜单类型预设（gainers/losers/turnover/amount/volume_ratio/amplitude）；为空默认 gainers",
            example = "gainers")
    private String rankType;

    @Schema(description = "申万一级行业 index_code 过滤（可选）")
    private String industryCode;

    @Schema(description = "市场过滤：沪市/深市 按代码后缀，创业板/科创板/北交所 按 stock_basic.market（可选）")
    private String market;

    @Schema(description = "排序字段（覆盖 rankType 默认排序）：pctChg/vol/amount/turnoverRate/totalMv/peTtm/pb/volumeRatio/amplitude（可选）")
    private String sortBy;

    @Schema(description = "排序方向 asc/desc（可选，默认随 rankType）")
    private String order;

    @Schema(description = "页码（从 1 开始，默认 1）", example = "1")
    private Integer page = 1;

    @Schema(description = "每页条数（默认 50，最大 500）", example = "50")
    private Integer size = 50;
}
