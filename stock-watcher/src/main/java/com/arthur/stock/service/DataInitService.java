package com.arthur.stock.service;

import com.arthur.stock.dto.DataInitProgress;

import java.util.List;

/**
 * 数据初始化服务，负责清除旧数据并从 Tushare 全量拉取指定接口的数据
 */
public interface DataInitService {

    /**
     * 异步触发数据初始化，可指定要执行的步骤。
     * 不传 steps 则执行全部步骤。每步执行前先清除该步骤对应的表数据。
     *
     * @param steps 要执行的步骤列表（code），为空则执行全部
     * @return 初始进度信息
     */
    DataInitProgress initialize(List<String> steps);

    /**
     * 查询当前初始化进度
     */
    DataInitProgress getStatus();

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
