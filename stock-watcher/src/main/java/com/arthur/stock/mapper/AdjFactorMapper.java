package com.arthur.stock.mapper;

import com.arthur.stock.model.AdjFactorDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 复权因子数据访问层，基于MyBatis-Plus BaseMapper提供对adj_factor表的CRUD操作
 */
@Mapper
public interface AdjFactorMapper extends BaseMapper<AdjFactorDO> {

    int insertBatch(@Param("list") List<AdjFactorDO> list);

    int deleteBatchByKeys(@Param("list") List<AdjFactorDO> list);

    /**
     * 一次性查出所有股票的最新交易日期（ts_code -&gt; latest_trade_date）。
     * 用于增量更新前预加载，避免逐只股票 N+1 查询。
     */
    List<Map<String, Object>> selectLatestDatePerStock();

    String selectLatestTradeDate();

    /**
     * 统计最近 N 天内关键字段为 NULL 或复权因子 <= 0 的异常记录数。
     * 用于检测数据拉取/写入过程中是否有脏数据。
     */
    int countNullInvalidRecords(@Param("startDate") String startDate);

    /**
     * 统计最近 N 天内的重复主键记录数（同一 ts_code + trade_date 出现多次）。
     * 正常应该为 0，大于 0 说明保存逻辑有 bug。
     */
    int countDuplicateRecords(@Param("startDate") String startDate);

    int countMissingInAdjFactor(@Param("startDate") String startDate);
}
