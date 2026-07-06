package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 因子预计算快照数据对象，对应 factor_snapshot 表。
 * <p>
 * 一行 = 一个交易日 × 一只股票 × 一个因子签名（factorKey+params+outputIndex）的当日值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("factor_snapshot")
public class FactorSnapshotDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 交易日期 yyyyMMdd */
    private String tradeDate;

    /** 股票代码 */
    private String tsCode;

    /** 因子 key（MA / MACD / KDJ ...） */
    private String factorKey;

    /** 参数 JSON（排序后），如 {"timeperiod":5} */
    private String paramsJson;

    /** 多输出因子切片（MACD 0/1/2） */
    private Integer outputIndex;

    /** 因子当日值，NaN 存 null */
    private BigDecimal factorValue;

    /** 更新时间 ISO */
    private String updatedAt;
}
