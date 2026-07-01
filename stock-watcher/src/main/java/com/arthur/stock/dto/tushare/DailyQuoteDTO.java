package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare daily 接口返回的日线行情数据
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=27">Tushare daily 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyQuoteDTO {

    /** 股票代码，如 000001.SZ */
    @JSONField(name = "ts_code")
    private String tsCode;

    /** 交易日期，格式 yyyyMMdd */
    @JSONField(name = "trade_date")
    private String tradeDate;

    /** 开盘价 */
    private BigDecimal open;

    /** 最高价 */
    private BigDecimal high;

    /** 最低价 */
    private BigDecimal low;

    /** 收盘价 */
    private BigDecimal close;

    /** 昨收价 */
    @JSONField(name = "pre_close")
    private BigDecimal preClose;

    /** 涨跌额 */
    @JSONField(name = "change")
    private BigDecimal change;

    /** 涨跌幅（%） */
    @JSONField(name = "pct_chg")
    private BigDecimal pctChg;

    /** 成交量（手） */
    private BigDecimal vol;

    /** 成交额（千元） */
    private BigDecimal amount;
}