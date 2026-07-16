package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 涨跌停价数据对象，对应 stock_stk_limit 表（tushare stk_limit，doc_id=183）
 * <p>
 * 用于精确判定某日某标的是否涨停/跌停（close &gt;= up_limit / close &lt;= down_limit）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_stk_limit")
public class StockStkLimitDO {

    /** 股票代码，如 000001.SZ */
    @TableField("ts_code")
    private String tsCode;

    /** 交易日（YYYYMMDD） */
    @TableField("trade_date")
    private String tradeDate;

    /** 前收盘价 */
    @TableField("pre_close")
    private Double preClose;

    /** 涨停价 */
    @TableField("up_limit")
    private Double upLimit;

    /** 跌停价 */
    @TableField("down_limit")
    private Double downLimit;
}
