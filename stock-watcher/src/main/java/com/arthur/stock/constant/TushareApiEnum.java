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
            "ts_code,ann_date,end_date,roe,roa,grossprofit_margin,netprofit_margin,dt_netprofit_yoy,revenue_yoy,debt_to_assets,eps_yoy"),

    /** 指数成分和权重接口 */
    INDEX_WEIGHT("index_weight",
            "ts_code,trade_date,con_code,weight"),

    /** 申万行业分类接口（index_classify，按 src=SWS2021 取 2021 版本） */
    INDEX_CLASSIFY("index_classify",
            "index_code,index_name,level,parent_code"),

    /** 申万行业成分股接口（index_member_all，全量成分股进出记录） */
    INDEX_MEMBER_ALL("index_member_all",
            "ts_code,index_code,index_name,in_date,out_date,is_new"),

    /** 股票更名历史（ST 戴帽摘帽，doc_id=160） */
    NAMECHANGE("namechange",
            "ts_code,name,start_date,end_date,change_reason"),

    /** 停复牌信息（doc_id=161） */
    SUSPEND_D("suspend_d",
            "ts_code,trade_date,susp_reason,resump_date"),

    /** 涨跌停价（doc_id=183） */
    STK_LIMIT("stk_limit",
            "ts_code,trade_date,pre_close,up_limit,down_limit"),

    /** 利润表（doc_id=33） */
    INCOME("income",
            "ts_code,ann_date,f_ann_date,end_date,report_type,comp_type,basic_eps,diluted_eps,"
                    + "total_revenue,revenue,total_cogs,operate_cost,operate_profit,non_oper_income,non_oper_exp,"
                    + "total_profit,n_income,n_income_attr_p,minority_interest,adjust_profit,income_tax,"
                    + "n_income_yoy,dt_profit_yoy,sell_exp,admin_exp,financial_exp,rd_exp,impair_end_invest,"
                    + "impair_end_oper,invest_income,invest_income_inc,invest_income_dec,fairvalue_change_income,"
                    + "exchange_gain,asset_dispose_income,other_income,operate_n_income,credit_impair_loss,"
                    + "asset_impair_loss,bbit,bbit_yoy,operate_profit_income_yoy,update_flag"),

    /** 资产负债表（doc_id=36） */
    BALANCESHEET("balancesheet",
            "ts_code,ann_date,f_ann_date,end_date,report_type,comp_type,monetary_funds,accounts_rece,"
                    + "notes_rece,accounts_rece_fin,other_rece,prepayment,dividends_rece,int_rece,inventories,"
                    + "non_current_assets_in_1_yr,other_current_assets,total_current_assets,equity_joint_cap,"
                    + "lt_receivable,eqt_invest,inv_real_estate,fix_assets_nca,cip,construction_materials,intang_assets,"
                    + "goodwill,lt_amort_deferred_exp,defer_tax_assets,other_non_current_assets,total_non_current_assets,"
                    + "total_assets,lt_borr,notes_payable,accounts_payable,accounts_payable_fin,prepayment_receivables,"
                    + "wage_payable,taxes_surcharges,other_payable,non_current_liab_in_1_yr,other_current_liab,"
                    + "total_current_liab,long_term_borr,ppayable_bonds,long_term_payable,specific_payable,"
                    + "estimated_liab,defer_tax_liab,defer_inc_non_curr_liab,other_non_current_liab,"
                    + "total_non_current_liab,total_liab,share_capital,capital_reserve,treasury_stock,"
                    + "specific_reserves,surplus_reserve,general_risk_reserve,undistributed_profit,"
                    + "equity_parent_company,minority_interest,total_equity,total_liab_equity,"
                    + "accounts_rece_decr,accounts_rece_fin_decr,minority_interest_inc,minority_interest_dec,"
                    + "update_flag"),

    /** 现金流量表（doc_id=44） */
    CASHFLOW("cashflow",
            "ts_code,ann_date,f_ann_date,end_date,report_type,comp_type,"
                    + "n_cashflow_act,n_cashflow_inv_act,n_cash_flows_fnc_act,free_cashflow,"
                    + "c_fr_sale_sg,c_fr_oth_sg,c_paid_goods_s,c_paid_to_for_empl,c_paid_for_taxes,"
                    + "c_paid_oth_op_f,c_fr_fnc_loan,c_fr_fnc_oth,c_paid_invest,c_paid_invest_f,"
                    + "c_paid_fin_fees,c_pay_dist_dpcp_int_exp,c_pay_acq_const_fiolta,c_pay_acq_int_long_loan,"
                    + "proceeds_long_loan,n_invest_loss,disp_fix_assets_oth,"
                    + "end_bal_cash,beg_bal_cash,n_cash_equ,n_increase_incl_child,"
                    + "prov_depr_assets,depr_fa_coga_dpba,amort_intang,amort_lt_deferred_exp,loss_disp_fa,"
                    + "loss_scr_fa,loss_fair_valu,fin_exp,loss_inv,dec_def_inc_tax_assets,inc_def_inc_tax_liab,"
                    + "dec_inv,dec_oper_rece,inc_oper_payable,net_profit,minority_interest,undistributed_profit_in,"
                    + "update_flag");

    private final String apiName;
    private final String fields;

    TushareApiEnum(String apiName, String fields) {
        this.apiName = apiName;
        this.fields = fields;
    }
}
