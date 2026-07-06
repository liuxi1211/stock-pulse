package com.arthur.stock.constant;

import lombok.Getter;

/**
 * Tushare API接口名称枚举，每个枚举值关联对应的接口名称和输出字段列表
 */
@Getter
public enum TushareApiEnum {

    /** 日线行情接口 */
    DAILY("daily",
            "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount"),

    /** 股票基础信息接口 */
    STOCK_BASIC("stock_basic",
            "ts_code,symbol,name,area,industry,fullname,enname,cnspell,market,exchange,curr_type,list_status,list_date,delist_date,is_hs,act_name,act_ent_type"),

    /** 交易日历接口 */
    TRADE_CAL("trade_cal",
            "exchange,cal_date,is_open,pretrade_date"),

    /** 复权因子接口 */
    ADJ_FACTOR("adj_factor",
            "ts_code,trade_date,adj_factor"),

    /** 分红送股接口 */
    DIVIDEND("dividend",
            "ts_code,end_date,ann_date,div_proc,stk_div,stk_bo_rate,stk_co_rate,cash_div,cash_div_tax,record_date,ex_date,pay_date,div_listdate,imp_ann_date,base_date,base_share"),

    /** 每日基本面接口（估值/换手率/市值） */
    DAILY_BASIC("daily_basic",
            "ts_code,trade_date,close,turnover_rate,turnover_rate_f,volume_ratio,pe,pe_ttm,pb,ps,ps_ttm,dv_ratio,dv_ttm,total_share,float_share,free_share,total_mv,circ_mv"),

    /** 财务指标接口（ROE/ROA/毛利率/同比/资产负债率等） */
    FINA_INDICATOR("fina_indicator",
            "ts_code,ann_date,end_date,roe,roa,grossprofit_margin,netprofit_margin,dt_netprofit_yoy,revenue_yoy,debt_to_assets,eps_yoy");

    private final String apiName;
    private final String fields;

    TushareApiEnum(String apiName, String fields) {
        this.apiName = apiName;
        this.fields = fields;
    }
}
