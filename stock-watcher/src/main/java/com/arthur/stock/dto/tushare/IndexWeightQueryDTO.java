package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare index_weight 接口请求参数
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=97">Tushare 指数成分和权重接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexWeightQueryDTO {

    /** 指数代码，如 000300.SH（可选） */
    private String indexCode;

    /** 交易日期，格式 yyyyMMdd（可选） */
    private String tradeDate;

    /** 开始日期，格式 yyyyMMdd（可选） */
    private String startDate;

    /** 结束日期，格式 yyyyMMdd（可选） */
    private String endDate;
}
