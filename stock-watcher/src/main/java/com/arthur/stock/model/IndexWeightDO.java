package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 指数成分股权重数据对象，对应 index_weight 表（tushare index_weight）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("index_weight")
public class IndexWeightDO {

    /** 指数代码，如 000300.SH */
    @TableField("ts_code")
    private String tsCode;

    /** 交易日期，格式 yyyyMMdd */
    @TableField("trade_date")
    private String tradeDate;

    /** 成分股代码，如 000001.SZ */
    @TableField("con_code")
    private String conCode;

    /** 成分股权重（%） */
    @TableField("weight")
    private BigDecimal weight;
}
