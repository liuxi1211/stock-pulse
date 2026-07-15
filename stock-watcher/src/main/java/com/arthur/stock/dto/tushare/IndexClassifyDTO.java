package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare index_classify 接口返回的申万行业分类数据
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=181">Tushare 申万行业分类接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexClassifyDTO {

    /** 行业代码 */
    @JSONField(name = "index_code")
    private String indexCode;

    /** 行业名称 */
    @JSONField(name = "index_name")
    private String indexName;

    /** 行业层级（1/2/3） */
    private Integer level;

    /** 父级行业代码（一级行业为空） */
    @JSONField(name = "parent_code")
    private String parentCode;
}
