package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stk_holdertrade")
public class StkHoldertradeDO {

    private Long id;
    private String tsCode;
    private String annDate;
    private String holderName;
    private String holderType;
    private String inDe;
    private BigDecimal changeVol;
    private BigDecimal changeRatio;
    private BigDecimal afterShare;
    private BigDecimal afterRatio;
    private BigDecimal avgPrice;
    private BigDecimal totalShare;
    private String beginDate;
    private String closeDate;
}
