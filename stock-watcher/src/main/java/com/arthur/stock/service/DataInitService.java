package com.arthur.stock.service;

import com.arthur.stock.constant.InitStep;

import java.util.List;

/**
 * 数据拉取服务，负责单表增量更新和全量重建
 */
public interface DataInitService {

    /**
     * 单表增量更新：从最新数据日期的下一天开始拉取到今天。
     *
     * @param tableCode 表代码（InitStep.code）
     * @param operator  操作人
     * @return taskId
     */
    String incrementalUpdate(String tableCode, String operator);

    /**
     * 单表全量重建：清空表后从头拉取全部历史数据。
     *
     * @param tableCode 表代码（InitStep.code）
     * @param operator  操作人
     * @return taskId
     */
    String fullRebuild(String tableCode, String operator);

    /**
     * 定时同步执行一组增量步骤。批次使用同一把全局锁，每个步骤独立记录拉取日志。
     *
     * @param batchName 批次名称
     * @param steps     按执行顺序排列的步骤
     */
    void scheduledIncrementalBatch(String batchName, List<InitStep> steps);

    /**
     * 定时同步执行单个全量步骤。
     *
     * @param batchName 批次名称
     * @param step      全量步骤
     */
    void scheduledFullUpdate(String batchName, InitStep step);
}
