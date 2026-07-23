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

    int countInvalidFactor(@Param("startDate") String startDate);

    int countMissingInAdjFactor(@Param("startDate") String startDate);
}
