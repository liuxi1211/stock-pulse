package com.arthur.stock.mapper;

import com.arthur.stock.model.StockStkLimitDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 涨跌停价数据访问层，基于 MyBatis-Plus BaseMapper 提供对 stock_stk_limit 表的 CRUD 操作。
 * <p>
 * 关键查询：区间批量查询（用于 buildKlineData 内存精确判定涨停/跌停）。
 */
@Mapper
public interface StockStkLimitMapper extends BaseMapper<StockStkLimitDO> {

    /**
     * 批量插入涨跌停价记录。
     */
    int insertBatch(@Param("list") List<StockStkLimitDO> list);

    /**
     * 按 (ts_code, trade_date) 批量删除。
     */
    int deleteBatchByKeys(@Param("list") List<StockStkLimitDO> list);

    /**
     * 区间批量：取多只股票在 [startDate, endDate] 的涨跌停价（供 buildKlineData 精确判定）。
     *
     * @param tsCodes   股票代码列表
     * @param startDate 起始日期 yyyyMMdd
     * @param endDate   结束日期 yyyyMMdd
     * @return 涨跌停价记录列表
     */
    @Select("<script>"
            + "SELECT ts_code, trade_date, pre_close, up_limit, down_limit FROM stock_stk_limit "
            + "WHERE trade_date BETWEEN #{startDate} AND #{endDate} "
            + "AND ts_code IN "
            + "<foreach item='c' collection='tsCodes' open='(' separator=',' close=')'>#{c}</foreach>"
            + "</script>")
    List<StockStkLimitDO> selectByTsCodesAndRange(@Param("tsCodes") List<String> tsCodes,
                                                   @Param("startDate") String startDate,
                                                   @Param("endDate") String endDate);
}
