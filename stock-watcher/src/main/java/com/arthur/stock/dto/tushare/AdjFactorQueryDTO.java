package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare adj_factor 接口请求参数
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=28">Tushare 复权因子接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdjFactorQueryDTO {

    /** 股票代码，如 000001.SZ（可选） */
    private String tsCode;

    /** 交易日期，格式 yyyyMMdd（可选） */
    private String tradeDate;

    /** 开始日期，格式 yyyyMMdd（可选） */
    private String startDate;

    /** 结束日期，格式 yyyyMMdd（可选） */
    private String endDate;
}
