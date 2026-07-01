package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 交易所枚举，覆盖证券交易所和期货交易所
 */
@Getter
public enum ExchangeEnum implements DisplayableEnum {

    /** 上海证券交易所 */
    SSE("SSE", "上交所"),
    /** 深圳证券交易所 */
    SZSE("SZSE", "深交所"),
    /** 北京证券交易所 */
    BSE("BSE", "北交所"),
    /** 中国金融期货交易所 */
    CFFEX("CFFEX", "中金所"),
    /** 上海期货交易所 */
    SHFE("SHFE", "上期所"),
    /** 郑州商品交易所 */
    CZCE("CZCE", "郑商所"),
    /** 大连商品交易所 */
    DCE("DCE", "大商所"),
    /** 上海国际能源交易中心 */
    INE("INE", "上能源");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    ExchangeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static ExchangeEnum fromCode(String code) {
        if (code == null) return null;
        for (ExchangeEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
