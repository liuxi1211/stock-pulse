package com.arthur.stock.dto.factor;

import com.arthur.stock.vo.FactorParamVO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "新增因子请求参数")
public class FactorCreateRequestDTO {

    @NotBlank(message = "factorKey 不能为空")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "factorKey 必须是大写蛇形命名，如 NEW_FACTOR")
    @Size(max = 100, message = "factorKey 长度不能超过 100")
    @Schema(description = "因子唯一标识，大写蛇形命名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String factorKey;

    @Size(max = 200, message = "displayName 长度不能超过 200")
    @Schema(description = "因子展示名称")
    private String displayName;

    @Size(max = 50, message = "category 长度不能超过 50")
    @Schema(description = "因子分类，如 OVERLAP、MOMENTUM 等")
    private String category;

    @Pattern(regexp = "^(AKQUANT|TUSHARE|RAW|DERIVED)$", message = "source 必须是 AKQUANT/TUSHARE/RAW/DERIVED 之一")
    @Schema(description = "因子来源：AKQUANT / TUSHARE / RAW / DERIVED")
    private String source;

    @Size(max = 100, message = "akquantFunc 长度不能超过 100")
    @Schema(description = "akquant.talib 函数名（AKQUANT/DERIVED 类型）")
    private String akquantFunc;

    @Size(max = 100, message = "tushareField 长度不能超过 100")
    @Schema(description = "Tushare 字段名（TUSHARE 类型）")
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
    @Schema(description = "回看长度表达式（基于参数）")
    private String lookbackHint;

    @Schema(description = "默认参数下的回看长度（bar 数）")
    private Integer lookbackDefault;
}
