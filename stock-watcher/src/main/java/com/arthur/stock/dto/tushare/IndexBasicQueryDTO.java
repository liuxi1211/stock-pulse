package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare index_basic 接口请求参数（所有字段均可选，全空则拉取全部市场）
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=94">Tushare 指数基本信息接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexBasicQueryDTO {

    /** 市场（SSE/SZSE/CSI/SW/MSCI/CICC/SWHK/OTH），可选 */
    private String market;

    /** TS指数代码，可选 */
    private String tsCode;

    /** 发布商，可选 */
    private String publisher;

    /** 指数类别，可选 */
    private String category;

    /** 分页偏移量 */
    private Integer offset;

    /** 分页每页条数 */
    private Integer limit;
}
