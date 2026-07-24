package com.arthur.stock.mapper;

import com.arthur.stock.model.StockSuspendDDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 股票停复牌数据访问层，基于 MyBatis-Plus BaseMapper 提供对 stock_suspend_d 表的 CRUD 操作。
 * <p>
 * 事件模型：每条记录为某日的停复牌事件（S=停牌，R=复牌）。
 * 关键查询：区间批量查询、截止日期查询（供 listSuspendDates 状态机计算）。
 */
@Mapper
public interface StockSuspendDMapper extends BaseMapper<StockSuspendDDO> {

    /**
     * 批量插入停复牌记录。
     */
    int insertBatch(@Param("list") List<StockSuspendDDO> list);

    /**
     * 按 (ts_code, trade_date) 批量删除。
     */
    int deleteBatchByKeys(@Param("list") List<StockSuspendDDO> list);

    /**
     * 当日是否有全天停牌事件。
     *
     * @param tsCode    股票代码
     * @param tradeDate 交易日 yyyyMMdd
     * @return 命中返回 1，否则 null
     */
    @Select("SELECT 1 FROM stock_suspend_d WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} "
            + "AND suspend_type = 'S' AND (suspend_timing IS NULL OR suspend_timing = '') LIMIT 1")
    Integer existsFullDaySuspendAt(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);

    /**
     * 区间批量：取多只股票在 [startDate, endDate] 的全部事件记录。
     *
     * @param tsCodes   股票代码列表
     * @param startDate 起始日期 yyyyMMdd
     * @param endDate   结束日期 yyyyMMdd
     * @return 事件记录列表
     */
    @Select("<script>"
            + "SELECT ts_code, trade_date, suspend_timing, suspend_type FROM stock_suspend_d "
            + "WHERE trade_date BETWEEN #{startDate} AND #{endDate} "
            + "AND ts_code IN "
            + "<foreach item='c' collection='tsCodes' open='(' separator=',' close=')'>#{c}</foreach>"
            + " ORDER BY ts_code, trade_date"
            + "</script>")
    List<StockSuspendDDO> selectByTsCodesAndRange(@Param("tsCodes") List<String> tsCodes,
                                                  @Param("startDate") String startDate,
                                                  @Param("endDate") String endDate);

    /**
     * 取指定股票截止到 endDate 的全部事件（用于状态机推导停牌日期）。
     * <p>
     * 包含 endDate 当日事件；状态机需要知道区间起点之前的状态，因此取所有历史（截止 endDate）。
     *
     * @param tsCodes 股票代码列表
     * @param endDate 截止日期 yyyyMMdd（含）
     * @return 事件记录列表，按 ts_code, trade_date 升序
     */
    @Select("<script>"
            + "SELECT ts_code, trade_date, suspend_timing, suspend_type FROM stock_suspend_d "
            + "WHERE trade_date &lt;= #{endDate} "
            + "AND ts_code IN "
            + "<foreach item='c' collection='tsCodes' open='(' separator=',' close=')'>#{c}</foreach>"
            + " ORDER BY ts_code, trade_date"
            + "</script>")
    List<StockSuspendDDO> selectEventsByTsCodesUpToDate(@Param("tsCodes") List<String> tsCodes,
                                                        @Param("endDate") String endDate);

    /** 查询最大的 trade_date，用于增量更新 */
    String selectMaxTradeDate();

    /** 统计 suspend_type 非 S/R 的异常记录数（NULL 或其他值） */
    int countInvalidType();

    /** 查询所有事件，按 ts_code, trade_date 升序（供 Java 层做事件序列检测） */
    @Select("SELECT ts_code, trade_date, suspend_timing, suspend_type FROM stock_suspend_d "
            + "ORDER BY ts_code, trade_date")
    List<StockSuspendDDO> selectAllEventsOrderByCodeAndDate();
}
