package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 多因子选股锁定记录数据对象，对应 screen_lock 表
 */
@Data
@TableName("screen_lock")
public class ScreenLockDO {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联结果ID */
    private Long resultId;

    /** 冗余方案ID（便于按方案查锁定） */
    private Long planId;

    /** 锁定日期（YYYYMMDD） */
    private String lockDate;

    /** 锁定时刻组合 JSON（与 screen_result.stocks_json 同结构） */
    private String stocksJson;

    /** 锁定后 5 交易日等权组合收益率 */
    private BigDecimal ret5d;

    /** 锁定后 10 交易日收益率 */
    private BigDecimal ret10d;

    /** 锁定后 20 交易日收益率 */
    private BigDecimal ret20d;

    /** 基准（沪深300）同期 5 交易日收益率 */
    private BigDecimal benchmarkRet5d;

    /** 基准（沪深300）同期 10 交易日收益率 */
    private BigDecimal benchmarkRet10d;

    /** 基准（沪深300）同期 20 交易日收益率 */
    private BigDecimal benchmarkRet20d;

    /** 追踪状态（TRACKING / DONE） */
    private String status;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（追踪完成后更新） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
