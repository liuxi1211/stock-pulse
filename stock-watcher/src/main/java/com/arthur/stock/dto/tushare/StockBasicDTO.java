package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare stock_basic 接口返回的股票基础信息
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=25">Tushare stock_basic 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockBasicDTO {

    /** TS代码 */
    @JSONField(name = "ts_code")
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

    /** 场类型（主板/创业板/科创板/CDR） */
    private String market;

    /** 交易所代码 */
    private String exchange;

    /** 交易货币 */
    @JSONField(name = "curr_type")
    private String currType;

    /** 上市状态 L上市 D退市 G过会未交易 P暂停上市 */
    @JSONField(name = "list_status")
    private String listStatus;

    /** 上市日期 */
    @JSONField(name = "list_date")
    private String listDate;

    /** 退市日期 */
    @JSONField(name = "delist_date")
    private String delistDate;

    /** 是否沪深港通标的 N否 H沪股通 S深股通 */
    @JSONField(name = "is_hs")
    private String isHs;

    /** 实控人名称 */
    @JSONField(name = "act_name")
    private String actName;

    /** 实控人企业性质 */
    @JSONField(name = "act_ent_type")
    private String actEntType;
}