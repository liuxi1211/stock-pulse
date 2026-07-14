package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 回测报告表数据对象，对应 quant_backtest_report 表。
 * <p>
 * 存储 SINGLE 模式回测的全量结果 JSON（metrics/equity_curve/benchmark_curve/trades 等），
 * 与 quant_backtest 一对一（UNIQUE(backtest_id)）。
 */
@Data
@TableName("quant_backtest_report")
public class QuantBacktestReportDO {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 回测主表ID（quant_backtest.id） */
    private Long backtestId;

    /** 指标集合 JSON（sharpe/return/drawdown 等） */
    private String metricsJson;

    /** 权益曲线 JSON（{dates, values}） */
    private String equityCurveJson;

    /** 基准归一化净值曲线 JSON（{dates, values}） */
    private String benchmarkCurveJson;

    /** 日收益率序列 JSON */
    private String dailyReturnsJson;

    /** 交易明细列表 JSON */
    private String tradesJson;

    /** 订单列表 JSON */
    private String ordersJson;

    /** 持仓快照列表 JSON */
    private String positionsJson;

    /** 创建时间（UTC ISO8601） */
    private String createdAt;
}
