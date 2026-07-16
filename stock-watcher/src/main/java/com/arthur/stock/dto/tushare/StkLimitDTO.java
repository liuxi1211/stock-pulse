package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare stk_limit 接口返回（涨跌停价，doc_id=183）
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=183">Tushare 涨跌停价接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StkLimitDTO {

    /** 股票代码，如 000001.SZ */
    @JSONField(name = "ts_code")
    private String tsCode;

    /** 交易日（YYYYMMDD） */
    @JSONField(name = "trade_date")
    private String tradeDate;

    /** 前收盘价 */
    @JSONField(name = "pre_close")
    private Double preClose;

    /** 涨停价 */
    @JSONField(name = "up_limit")
    private Double upLimit;

    /** 跌停价 */
    @JSONField(name = "down_limit")
    private Double downLimit;
}
