package com.arthur.stock.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 锁定组合个股贡献明细（spec 003 阶段 2 Task 11，FR-9）。
 * <p>
 * 以"当前最新交易日"为终点，展示每只个股相对锁定日的收益与等权贡献。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StockContributionVO {

    /** 股票代码（tsCode） */
    private String symbol;

    /** 锁定日收盘价 */
    private BigDecimal lockClose;

    /** 当前（最新交易日）收盘价；停牌/无数据时为 null */
    private BigDecimal currentClose;

    /** 个股收益率（小数，0.05 表示 5%） */
    private BigDecimal returnPct;

    /** 等权贡献（= 个股收益率 / 股票数） */
    private BigDecimal contributionPct;
}
