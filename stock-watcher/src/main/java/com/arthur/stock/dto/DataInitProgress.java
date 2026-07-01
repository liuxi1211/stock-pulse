package com.arthur.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据初始化进度信息
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DataInitProgress {

    /** 状态：IDLE / RUNNING / SUCCESS / FAILED */
    private String status;

    /** 当前执行步骤描述 */
    private String currentStep;

    /** 股票总数 */
    private int totalStocks;

    /** 当前步骤已处理的股票数 */
    private int processedStocks;

    /** 附加消息（错误信息等） */
    private String message;

    /** 开始时间 */
    private String startTime;

    /** 结束时间 */
    private String endTime;
}
