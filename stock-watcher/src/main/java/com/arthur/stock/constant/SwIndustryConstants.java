package com.arthur.stock.constant;

/**
 * 申万行业分类相关常量。
 * <p>
 * src 取值与 Tushare {@code index_classify} / {@code index_member_all} 接口实际返回值保持一致。
 */
public final class SwIndustryConstants {

    private SwIndustryConstants() {
    }

    /**
     * 申万行业分类版本标识，对应 Tushare index_classify 接口的 src 参数及返回值。
     * <p>
     * 注意：Tushare 返回值为 {@code "SW2021"}（申万 2021 版本），非 "SWS2021"。
     * 库内 {@code sw_industry.src} / {@code sw_industry_member.src} 列均以此值存储。
     */
    public static final String SW_SRC = "SW2021";
}
