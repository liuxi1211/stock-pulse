package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare index_member_all 接口请求参数（申万行业成分股）
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=335">Tushare 行业成分股接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexMemberQueryDTO {

    /** 股票代码，如 000001.SZ（可选） */
    private String tsCode;

    /** 行业代码（可选） */
    private String indexCode;

    /** 分类版本，如 SWS2021（默认） */
    private String src;
}
