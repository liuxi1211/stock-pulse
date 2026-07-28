package com.arthur.stock.mapper;

import com.arthur.stock.model.TopListDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 龙虎榜个股明细数据访问层（top_list 表）。
 * <p>
 * 无主键。Tushare 可能返回 trade_date+ts_code+name+reason 相同但金额不同的多条记录（不同统计口径）。
 * 幂等更新依赖 deleteBatchByKeys 按 (trade_date, ts_code, name, reason) 删除后重新插入。
 */
@Mapper
public interface TopListMapper extends BaseMapper<TopListDO> {

    /** 按 (trade_date, ts_code, name, reason) 批量删除。 */
    int deleteBatchByKeys(@Param("list") List<TopListDO> list);

    /** 批量插入。 */
    int insertBatch(@Param("list") List<TopListDO> list);

    /** 查某交易日的龙虎榜个股列表，按净额降序。 */
    List<TopListDO> selectByTradeDate(@Param("tradeDate") String tradeDate);

    /**
     * 查某股票在指定交易日区间内的龙虎榜个股明细（按 trade_date 倒序）。
     *
     * @param tsCode    股票代码，如 000001.SZ
     * @param startDate 开始日期 yyyyMMdd
     * @param endDate   结束日期 yyyyMMdd
     */
    List<TopListDO> selectByCodeAndDateRange(@Param("tsCode") String tsCode,
                                              @Param("startDate") String startDate,
                                              @Param("endDate") String endDate);

    /** 取 top_list 表中最新的交易日。 */
    String selectLatestTradeDate();

    int countInvalidAmount(@Param("startDate") String startDate);

    int countNetAmountInconsistency(@Param("startDate") String startDate);
}
