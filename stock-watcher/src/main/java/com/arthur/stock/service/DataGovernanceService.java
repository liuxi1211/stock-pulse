package com.arthur.stock.service;

import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.model.DataGovernanceMetricDO;

import java.util.List;

/**
 * 数据治理服务：数据质量检测、状态查询。
 */
public interface DataGovernanceService {

    /**
     * 检测单张表，保存检测结果，返回检测结果。
     *
     * @param tableCode 表代码（对应 InitStep.code）
     * @return 检测结果
     */
    DataCheckResult checkTable(String tableCode);

    /**
     * 异步检测全部业务表（25 张），返回任务ID（供前端轮询进度）。
     *
     * @return taskId（检查任务的唯一ID，用于查询进度）
     */
    String checkAll();

    /**
     * 同步检测全部业务表（供定时任务调用），返回批次ID。
     *
     * @return 批次ID
     */
    String checkAllScheduled();

    /**
     * 获取最新一次检测批次的全部记录。
     */
    List<DataGovernanceMetricDO> getLatestBatch();

    /**
     * 获取某张表最新的检测记录。
     *
     * @param tableCode 表代码
     * @return 最新检测记录，无则 null
     */
    DataGovernanceMetricDO getLatestMetric(String tableCode);

    /**
     * 获取某张表的当前状态（NORMAL/DELAYED/ERROR/UPDATING），考虑正在运行的任务。
     *
     * @param tableCode 表代码
     * @return 状态字符串
     */
    String getTableStatus(String tableCode);

    /**
     * 获取全部表的最新状态（含实时 UPDATING 覆盖）。
     *
     * @return 最新批次记录列表，正在运行任务时 status 被覆盖为 UPDATING
     */
    List<DataGovernanceMetricDO> getAllTableStatuses();

    /**
     * 获取某张表的历史检测记录（按时间倒序，最多 limit 条）。
     *
     * @param tableCode 表代码
     * @param limit     最大返回条数
     * @return 历史检测记录列表
     */
    List<DataGovernanceMetricDO> getMetricHistory(String tableCode, int limit);
}
