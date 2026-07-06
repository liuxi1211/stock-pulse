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

    /**
     * 批量取多只股票在 [startDate, endDate] 的 OHLCV（按 ts_code、trade_date 升序）。
     * <p>
     * 替代旧逻辑里逐股 {@code queryLocalByTsCode} 全量查询再截断的 N+1 写法。
     * 依赖 daily_quote 主键索引 (ts_code, trade_date)。
     */
    List<DailyQuoteDO> selectOhlcvByCodesAndDateRange(@Param("codes") List<String> codes,
                                                     @Param("startDate") String startDate,
                                                     @Param("endDate") String endDate);

    /**
     * 一次性取多只股票在某交易日的行情（主要用于批量取收盘价）。
     */
    List<DailyQuoteDO> selectByCodesAndTradeDate(@Param("codes") List<String> codes,
                                                 @Param("tradeDate") String tradeDate);

    /**
     * 全市场 distinct trade_date 升序，作为简化交易日历。替换旧 {@code selectList(null)} 全表加载。
     */
    List<String> selectDistinctTradeDatesAsc();
}
