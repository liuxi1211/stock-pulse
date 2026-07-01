package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare trade_cal 接口请求参数
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=26">Tushare trade_cal 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeCalQueryDTO {

    /** 交易所 SSE上交所 SZSE深交所 CFFEX中金所 SHFE上期所 CZCE郑商所 DCE大商所 INE上能源（可选） */
    private String exchange;

    /** 开始日期，格式 yyyyMMdd（可选） */
    private String startDate;

    /** 结束日期，格式 yyyyMMdd（可选） */
    private String endDate;

    /** 是否交易 0休市 1交易（可选） */
    private String isOpen;
}