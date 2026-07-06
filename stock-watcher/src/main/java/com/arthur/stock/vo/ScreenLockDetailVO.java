package com.arthur.stock.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 选股锁定记录追踪明细视图对象（spec 003 阶段 2 Task 11，FR-9）。
 * <p>
 * 继承基础字段，附加：解析后的个股列表 + 个股贡献明细。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScreenLockDetailVO extends ScreenLockVO {

    /** 锁定时刻的个股列表（解析自 stocksJson） */
    private List<LockedStockVO> stocks;

    /** 个股贡献明细（以当前最新交易日为终点） */
    private List<StockContributionVO> contributions;
}
