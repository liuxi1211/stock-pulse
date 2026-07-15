package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare index_member_all 接口返回的行业成分股数据
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=335">Tushare 行业成分股接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexMemberDTO {

    /** 股票代码，如 000001.SZ */
    @JSONField(name = "ts_code")
    private String tsCode;

    /** 行业代码 */
    @JSONField(name = "index_code")
    private String indexCode;

    /** 行业名称 */
    @JSONField(name = "index_name")
    private String indexName;

    /** 纳入日期（YYYYMMDD） */
    @JSONField(name = "in_date")
    private String inDate;

    /** 剔除日期（YYYYMMDD，为空表示当前在册） */
    @JSONField(name = "out_date")
    private String outDate;

    /** 是否最新（1=是，0=否） */
    @JSONField(name = "is_new")
    private String isNew;
}
