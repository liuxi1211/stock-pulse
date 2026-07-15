package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare index_classify 接口请求参数（申万行业分类）
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=181">Tushare 申万行业分类接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexClassifyQueryDTO {

    /** 分类版本，如 SWS2021（默认） */
    private String src;

    /** 层级（L1/L2/L3，可选） */
    private String level;
}
