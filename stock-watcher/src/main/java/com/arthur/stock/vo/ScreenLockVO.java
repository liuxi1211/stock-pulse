package com.arthur.stock.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 选股结果锁定记录视图对象（spec 003 阶段 2 Task 11，FR-9）。
 * <p>
 * 仅含基础字段，不含个股明细（明细见 {@link ScreenLockDetailVO}）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScreenLockVO {

    /** 主键ID */
    private Long id;

    /** 关联结果ID */
    private Long resultId;

    /** 冗余方案ID */
    private Long planId;

    /** 锁定日期（YYYYMMDD） */
    private String lockDate;

    /** 追踪状态（TRACKING / DONE） */
    private String status;

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

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
