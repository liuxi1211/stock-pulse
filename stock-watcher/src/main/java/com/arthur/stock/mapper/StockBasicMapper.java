package com.arthur.stock.mapper;

import com.arthur.stock.model.StockBasicDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 股票基础信息数据访问层，基于MyBatis-Plus BaseMapper提供对stock_basic表的CRUD操作
 */
@Mapper
public interface StockBasicMapper extends BaseMapper<StockBasicDO> {

    int insertBatch(@Param("list") List<StockBasicDO> list);

    int deleteBatchByKeys(@Param("list") List<StockBasicDO> list);

    // ==================== 数据管控检查 ====================

    /**
     * 统计在 daily_quote 中出现但不在 stock_basic 中的 distinct ts_code 数量
     */
    int countStocksInDailyNotInBasic(@Param("startDate") String startDate);

    /**
     * 统计最近 7 天 daily_quote 中的 distinct ts_code 数量
     */
    int countDistinctTsCodeInDaily(@Param("startDate") String startDate);

    /**
     * 统计关键字段为空的记录数
     */
    int countNullKeyFields();
}
