package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare stock_basic 接口请求参数
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=25">Tushare stock_basic 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockBasicQueryDTO {

    /** TS股票代码，如 000001.SZ（可选） */
    private String tsCode;

    /** 名称（可选） */
    private String name;

    /** 市场类别：主板/创业板/科创板/CDR/北交所（可选） */
    private String market;

    /** 上市状态 L上市 D退市 P暂停上市 G未交易，默认L（可选） */
    private String listStatus;

    /** 交易所 SSE上交所 SZSE深交所 BSE北交所（可选） */
    private String exchange;

    /** 是否沪深港通标的 N否 H沪股通 S深股通（可选） */
    private String isHs;
}