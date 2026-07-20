package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare forecast 接口返回的业绩预告数据。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=45">Tushare forecast 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForecastDTO {

    @JSONField(name = "ts_code")
    private String tsCode;

    @JSONField(name = "ann_date")
    private String annDate;

    @JSONField(name = "end_date")
    private String endDate;

    /** 业绩预告类型：预增/预减/扭亏/续盈/续亏/略增/略减/不确定 */
    private String type;

    @JSONField(name = "p_change_min")
    private BigDecimal pChangeMin;

    @JSONField(name = "p_change_max")
    private BigDecimal pChangeMax;

    @JSONField(name = "net_profit_min")
    private BigDecimal netProfitMin;

    @JSONField(name = "net_profit_max")
    private BigDecimal netProfitMax;

    @JSONField(name = "last_parent_net")
    private BigDecimal lastParentNet;

    /** 业绩预告内容 */
    private String summary;

    @JSONField(name = "change_reason")
    private String changeReason;
}
