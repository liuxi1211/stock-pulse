package com.arthur.stock.model;

import com.arthur.stock.constant.*;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票基础信息数据对象，对应 stock_basic 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_basic")
public class StockBasicDO {

    /** 主键ID */
    private Long id;

    /** TS股票代码，如 000001.SZ */
    private String tsCode;

    /** 股票代码 */
    private String symbol;

    /** 股票名称 */
    private String name;

    /** 地域 */
    private String area;

    /** 所属行业 */
    private String industry;

    /** 股票全称 */
    private String fullname;

    /** 英文全称 */
    private String enname;

    /** 拼音缩写 */
    private String cnspell;

    /** 市场类型（主板/创业板/科创板/CDR/北交所） */
    private BoardEnum market;

    /** 交易所（SSE/SZSE/BSE） */
    private ExchangeEnum exchange;

    /** 交易货币 */
    private String currType;

    /** 上市状态（L上市/D退市/P暂停上市/G未交易） */
    private ListStatusEnum listStatus;

    /** 上市日期 */
    private String listDate;

    /** 退市日期 */
    private String delistDate;

    /** 沪深港通标识（N否/H沪股通/S深股通） */
    private HsConnectEnum isHs;

    /** 实控人名称 */
    private String actName;

    /** 实控人企业性质 */
    private String actEntType;
}