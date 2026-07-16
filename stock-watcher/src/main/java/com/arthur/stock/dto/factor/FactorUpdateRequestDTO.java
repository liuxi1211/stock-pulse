package com.arthur.stock.dto.factor;

import com.arthur.stock.vo.FactorParamVO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 修改因子请求 DTO（与 engine FactorUpdateRequest 对齐）。
 * 不允许修改 factorKey，其余字段为可选 subset。
 */
@Data
@Schema(description = "更新因子请求参数")
public class FactorUpdateRequestDTO {

    @Size(max = 200, message = "displayName 长度不能超过 200")
    @Schema(description = "因子展示名称")
    private String displayName;

    @Size(max = 50, message = "category 长度不能超过 50")
    @Schema(description = "因子分类")
    private String category;

    @Pattern(regexp = "^(AKQUANT|TUSHARE|RAW|DERIVED)$", message = "source 必须是 AKQUANT/TUSHARE/RAW/DERIVED 之一")
    @Schema(description = "因子来源")
    private String source;

    @Size(max = 100, message = "akquantFunc 长度不能超过 100")
    @Schema(description = "akquant.talib 函数名")
    private String akquantFunc;

    @Size(max = 100, message = "tushareField 长度不能超过 100")
    @Schema(description = "Tushare 字段名")
    private String tushareField;

    @Size(max = 50, message = "dataSource 长度不能超过 50")
    @Schema(description = "数据来源标记")
    private String dataSource;

    @Size(max = 1000, message = "description 长度不能超过 1000")
    @Schema(description = "因子描述说明")
    private String description;

    @Schema(description = "参数定义列表")
    private List<FactorParamVO> params;

    @Schema(description = "所需 OHLCV 输入列")
    private List<String> inputs;

    @Schema(description = "是否多输出指标")
    private Boolean multiOutput;

    @Schema(description = "多输出标签名称")
    private List<String> outputLabels;

    @Schema(description = "默认输出序列下标")
    private Integer defaultOutputIndex;

    @Size(max = 100, message = "lookbackHint 长度不能超过 100")
    @Schema(description = "回看长度表达式")
    private String lookbackHint;

    @Schema(description = "默认参数下的回看长度")
    private Integer lookbackDefault;

    @Schema(description = "是否支持 transform 滚动窗口聚合")
    private Boolean transformable;
}
