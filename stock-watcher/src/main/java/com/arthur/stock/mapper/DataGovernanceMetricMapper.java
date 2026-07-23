package com.arthur.stock.mapper;

import com.arthur.stock.model.DataGovernanceMetricDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据质量检测历史数据访问层（data_governance_metric 表）。
 */
@Mapper
public interface DataGovernanceMetricMapper extends BaseMapper<DataGovernanceMetricDO> {

    /** 批量插入 */
    int insertBatch(@Param("list") List<DataGovernanceMetricDO> list);

    /** 查询最新一次检测批次的全部记录（check_time 最大的 check_batch_id） */
    List<DataGovernanceMetricDO> selectLatestBatch();

    /** 查询某张表最新的检测记录 */
    DataGovernanceMetricDO selectByTableCodeLatest(@Param("tableCode") String tableCode);

    /** 查询某张表上一次检测记录（用于行数变动对比） */
    DataGovernanceMetricDO selectPreviousByTableCode(@Param("tableCode") String tableCode);

    /** 删除早于指定时间的记录 */
    int deleteOlderThan(@Param("cutoffTime") String cutoffTime);

    /** 查询某张表的历史检测记录（按时间倒序，最多 limit 条） */
    List<DataGovernanceMetricDO> selectHistoryByTableCode(@Param("tableCode") String tableCode, @Param("limit") int limit);
}
