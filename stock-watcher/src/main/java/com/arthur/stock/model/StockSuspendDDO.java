package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票停复牌数据对象，对应 stock_suspend_d 表（tushare suspend_d，doc_id=161）
 * <p>
 * 用于判定某日某标的是否停牌。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_suspend_d")
public class StockSuspendDDO {

    /** 股票代码，如 000001.SZ */
    @TableField("ts_code")
    private String tsCode;

    /** 停牌日期（YYYYMMDD） */
    @TableField("trade_date")
    private String tradeDate;

    /** 停牌原因 */
    @TableField("susp_reason")
    private String suspReason;

    /** 复牌日期（YYYYMMDD，为空表示尚未复牌） */
    @TableField("resump_date")
    private String resumpDate;
}
