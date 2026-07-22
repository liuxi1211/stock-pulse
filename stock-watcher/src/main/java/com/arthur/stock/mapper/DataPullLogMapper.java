package com.arthur.stock.mapper;

import com.arthur.stock.model.DataPullLogDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据拉取日志数据访问层（data_pull_log 表）。
 */
@Mapper
public interface DataPullLogMapper extends BaseMapper<DataPullLogDO> {

    /** 单条插入 */
    int insert(DataPullLogDO log);

    /** 更新任务状态 */
    int updateStatus(@Param("taskId") String taskId, @Param("status") String status,
                     @Param("endTime") String endTime, @Param("durationMs") Long durationMs,
                     @Param("totalCount") Long totalCount, @Param("successCount") Long successCount,
                     @Param("failCount") Long failCount, @Param("errorMessage") String errorMessage,
                     @Param("errorStack") String errorStack);

    /** 查询某张表的最近日志 */
    List<DataPullLogDO> selectByTableCode(@Param("tableCode") String tableCode, @Param("limit") int limit);

    /** 分页查询（支持多条件过滤） */
    List<DataPullLogDO> selectPageList(@Param("tableCode") String tableCode,
                                       @Param("status") String status,
                                       @Param("operationType") String operationType,
                                       @Param("startTime") String startTime,
                                       @Param("endTime") String endTime,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);

    /** 分页查询总数 */
    long selectPageCount(@Param("tableCode") String tableCode,
                         @Param("status") String status,
                         @Param("operationType") String operationType,
                         @Param("startTime") String startTime,
                         @Param("endTime") String endTime);

    /** 按ID查询单条日志 */
    DataPullLogDO selectById(@Param("id") Long id);

    /** 删除早于指定时间的记录 */
    int deleteOlderThan(@Param("cutoffTime") String cutoffTime);
}
