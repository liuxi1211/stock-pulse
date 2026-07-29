package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare index_basic 接口返回的指数基础信息
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=94">Tushare 指数基本信息接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexBasicDTO {

    /** TS指数代码，如 000300.SH / 801010.SI */
    @JSONField(name = "ts_code")
    private String tsCode;

    /** 指数简称 */
    private String name;

    /** 指数全称 */
    private String fullname;

    /** 市场（SSE/SZSE/CSI/SW/MSCI/CICC/SWHK/OTH） */
    private String market;

    /** 发布商 */
    private String publisher;

    /** 指数风格 */
    @JSONField(name = "index_type")
    private String indexType;

    /** 指数类别 */
    private String category;

    /** 基期，格式 yyyyMMdd */
    @JSONField(name = "base_date")
    private String baseDate;

    /** 基点 */
    @JSONField(name = "base_point")
    private BigDecimal basePoint;

    /** 发布日期，格式 yyyyMMdd */
    @JSONField(name = "list_date")
    private String listDate;

    /** 加权方式 */
    @JSONField(name = "weight_rule")
    private String weightRule;
}
