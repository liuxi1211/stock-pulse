package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 指数基本信息数据对象，对应 index_basic 表
 * <p>
 * 数据源：tushare index_basic 接口（doc_id=94），覆盖全部市场的指数基础信息。
 * 主键：id（自增）；ts_code 上有 UNIQUE 约束，用作业务键。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("index_basic")
public class IndexBasicDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** TS指数代码，如 000300.SH / 801010.SI */
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
    private String indexType;

    /** 指数类别 */
    private String category;

    /** 基期，格式 yyyyMMdd */
    private String baseDate;

    /** 基点 */
    private BigDecimal basePoint;

    /** 发布日期，格式 yyyyMMdd */
    private String listDate;

    /** 加权方式 */
    private String weightRule;
}
