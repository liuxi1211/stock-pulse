package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 融资融券汇总数据对象，对应 margin 表（Tushare margin：融资融券汇总）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("margin")
public class MarginDO {

    /** 交易所代码（SSE/SZSE） */
    private String exchangeId;

    /** 交易日期 yyyyMMdd */
    private String tradeDate;

    /** 融资余额（万元） */
    private BigDecimal rzye;

    /** 融资买入额（万元） */
    private BigDecimal rzmre;

    /** 融资偿还额（万元） */
    private BigDecimal rzche;

    /** 融券余额（万元） */
    private BigDecimal rqye;

    /** 融券卖出量（万股） */
    private BigDecimal rqmcl;

    /** 融资融券余额（万元） */
    private BigDecimal rzrqye;
}
