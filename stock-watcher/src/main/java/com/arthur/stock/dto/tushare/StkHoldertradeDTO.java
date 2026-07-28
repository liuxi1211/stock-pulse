package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StkHoldertradeDTO {

    @JSONField(name = "ts_code")
    private String tsCode;

    @JSONField(name = "ann_date")
    private String annDate;

    @JSONField(name = "holder_name")
    private String holderName;

    @JSONField(name = "holder_type")
    private String holderType;

    @JSONField(name = "in_de")
    private String inDe;

    @JSONField(name = "change_vol")
    private BigDecimal changeVol;

    @JSONField(name = "change_ratio")
    private BigDecimal changeRatio;

    @JSONField(name = "after_share")
    private BigDecimal afterShare;

    @JSONField(name = "after_ratio")
    private BigDecimal afterRatio;

    @JSONField(name = "avg_price")
    private BigDecimal avgPrice;

    @JSONField(name = "total_share")
    private BigDecimal totalShare;

    @JSONField(name = "begin_date")
    private String beginDate;

    @JSONField(name = "close_date")
    private String closeDate;
}
