package com.arthur.stock.mapper;

import com.arthur.stock.model.DailyQuoteDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 日线行情数据访问层，基于MyBatis-Plus BaseMapper提供对daily_quote表的CRUD操作
 */
@Mapper
public interface DailyQuoteMapper extends BaseMapper<DailyQuoteDO> {

    @Insert("<script>" +
            "INSERT OR IGNORE INTO daily_quote (ts_code, trade_date, open, high, low, close, pre_close, " +
            "change_amt, pct_chg, vol, amount) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.tsCode}, #{item.tradeDate}, #{item.open}, #{item.high}, #{item.low}, #{item.close}, " +
            "#{item.preClose}, #{item.changeAmt}, #{item.pctChg}, #{item.vol}, #{item.amount})" +
            "</foreach>" +
            "</script>")
    int insertOrIgnoreBatch(@Param("list") List<DailyQuoteDO> list);

    @Select("SELECT trade_date, COUNT(DISTINCT ts_code) AS cnt " +
            "FROM daily_quote WHERE trade_date >= #{startDate} AND trade_date <= #{endDate} " +
            "GROUP BY trade_date")
    List<Map<String, Object>> selectTradeDateStockCount(@Param("startDate") String startDate,
                                                         @Param("endDate") String endDate);

    @Select("SELECT MAX(trade_date) FROM daily_quote")
    String selectLatestTradeDate();

    @Select("SELECT * FROM daily_quote WHERE trade_date = #{tradeDate} " +
            "AND ts_code IN (SELECT ts_code FROM stock_basic WHERE list_status = 'L') " +
            "ORDER BY pct_chg DESC LIMIT #{limit}")
    List<DailyQuoteDO> selectTopGainers(@Param("tradeDate") String tradeDate, @Param("limit") int limit);

    @Select("SELECT * FROM daily_quote WHERE trade_date = #{tradeDate} " +
            "AND ts_code IN (SELECT ts_code FROM stock_basic WHERE list_status = 'L') " +
            "ORDER BY pct_chg ASC LIMIT #{limit}")
    List<DailyQuoteDO> selectTopLosers(@Param("tradeDate") String tradeDate, @Param("limit") int limit);

    @Select("SELECT * FROM daily_quote WHERE trade_date = #{tradeDate} " +
            "AND ts_code IN (SELECT ts_code FROM stock_basic WHERE list_status = 'L') " +
            "ORDER BY amount DESC LIMIT #{limit}")
    List<DailyQuoteDO> selectTopAmount(@Param("tradeDate") String tradeDate, @Param("limit") int limit);
}