package com.arthur.stock.model;

import com.alibaba.fastjson2.annotation.JSONField;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 指数日线行情数据对象，对应 index_daily 表（tushare index_daily 接口）
 * <p>
 * 同时承载 Tushare 响应解析（{@code @JSONField} 映射 snake_case 字段）与 MyBatis-Plus 实体映射，
 * 因此 {@link com.arthur.stock.client.TushareClient#fetchIndexDaily} 可直接将其作为目标类型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("index_daily")
public class IndexDailyDO {

    /** 指数代码，如 000001.SH */
    private String tsCode;

    /** 交易日期，格式 yyyyMMdd */
    private String tradeDate;

    /** 收盘价 */
    private BigDecimal close;

    /** 开盘价 */
    private BigDecimal open;

    /** 最高价 */
    private BigDecimal high;

    /** 最低价 */
    private BigDecimal low;

    /** 昨收价 */
    private BigDecimal preClose;

    /** 涨跌额（DB 列名为 change，MySQL 中为保留字；Java 属性用 changeValue 规避歧义） */
    @TableField("change")
    @JSONField(name = "change")
    private BigDecimal changeValue;

    /** 涨跌幅（%） */
    private BigDecimal pctChg;

    /** 成交量（万手） */
    private BigDecimal vol;

    /** 成交额（亿元） */
    private BigDecimal amount;
}
