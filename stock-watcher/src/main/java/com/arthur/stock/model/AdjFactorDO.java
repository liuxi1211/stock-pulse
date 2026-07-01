package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 复权因子数据对象，对应 adj_factor 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("adj_factor")
public class AdjFactorDO {

    /** TS股票代码，如 000001.SZ */
    private String tsCode;

    /** 交易日期，格式 yyyyMMdd */
    private String tradeDate;

    /** 复权因子 */
    private BigDecimal adjFactor;
}
