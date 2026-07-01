package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare trade_cal 接口返回的交易日历数据
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=26">Tushare trade_cal 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeCalDTO {

    /** 交易所 SSE上交所 SZSE深交所 */
    private String exchange;

    /** 日历日期 */
    @JSONField(name = "cal_date")
    private String calDate;

    /** 是否交易 0休市 1交易 */
    @JSONField(name = "is_open")
    private String isOpen;

    /** 上一个交易日 */
    @JSONField(name = "pretrade_date")
    private String pretradeDate;
}