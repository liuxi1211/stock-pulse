package com.arthur.stock.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 锁定组合中的单只股票视图对象（spec 003 阶段 2 Task 11，FR-9）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LockedStockVO {

    /** 股票代码（tsCode） */
    private String symbol;

    /** 排名 */
    private Integer rank;

    /** 综合评分 */
    private BigDecimal score;
}
