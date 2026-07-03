package com.arthur.stock.mapper;

import com.arthur.stock.model.DailyQuoteDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 日线行情数据访问层，基于MyBatis-Plus BaseMapper提供对daily_quote表的CRUD操作
 */
@Mapper
public interface DailyQuoteMapper extends BaseMapper<DailyQuoteDO> {

    int insertBatch(@Param("list") List<DailyQuoteDO> list);

    int deleteBatchByKeys(@Param("list") List<DailyQuoteDO> list);

    List<Map<String, Object>> selectTradeDateStockCount(@Param("startDate") String startDate,
                                                         @Param("endDate") String endDate);

    String selectLatestTradeDate();

    List<DailyQuoteDO> selectTopGainers(@Param("tradeDate") String tradeDate, @Param("limit") int limit);

    List<DailyQuoteDO> selectTopLosers(@Param("tradeDate") String tradeDate, @Param("limit") int limit);

    List<DailyQuoteDO> selectTopAmount(@Param("tradeDate") String tradeDate, @Param("limit") int limit);
}
