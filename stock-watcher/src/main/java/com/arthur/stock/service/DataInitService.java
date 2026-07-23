package com.arthur.stock.service;

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
}
