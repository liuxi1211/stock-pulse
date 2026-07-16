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
 * 关键查询：当日是否停牌判定 + 区间批量查询（用于 buildKlineData 内存停牌过滤）。
 */
@Mapper
public interface StockSuspendDMapper extends BaseMapper<StockSuspendDDO> {

    /**
     * 当日是否停牌。
     *
     * @param tsCode    股票代码
     * @param tradeDate 交易日 yyyyMMdd
     * @return 命中返回 1，否则 null
     */
    @Select("SELECT 1 FROM stock_suspend_d WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} LIMIT 1")
    Integer existsSuspendedAt(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);

    /**
     * 区间批量：取多只股票在 [startDate, endDate] 的全部停牌记录（供 buildKlineData 内存判定）。
     *
     * @param tsCodes   股票代码列表
     * @param startDate 起始日期 yyyyMMdd
     * @param endDate   结束日期 yyyyMMdd
     * @return 停牌记录列表
     */
    @Select("<script>"
            + "SELECT ts_code, trade_date, susp_reason, resump_date FROM stock_suspend_d "
            + "WHERE trade_date BETWEEN #{startDate} AND #{endDate} "
            + "AND ts_code IN "
            + "<foreach item='c' collection='tsCodes' open='(' separator=',' close=')'>#{c}</foreach>"
            + "</script>")
    List<StockSuspendDDO> selectByTsCodesAndRange(@Param("tsCodes") List<String> tsCodes,
                                                  @Param("startDate") String startDate,
                                                  @Param("endDate") String endDate);
}
