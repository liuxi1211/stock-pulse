package com.arthur.stock.mapper;

import com.arthur.stock.model.TopListDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 龙虎榜个股明细数据访问层（top_list 表）。
 * <p>
 * 主键：(trade_date, ts_code, reason)
 */
@Mapper
public interface TopListMapper extends BaseMapper<TopListDO> {

    /** 按 (trade_date, ts_code, reason) 批量删除。 */
    int deleteBatchByKeys(@Param("list") List<TopListDO> list);

    /** 批量插入。 */
    int insertBatch(@Param("list") List<TopListDO> list);

    /** 查某交易日的龙虎榜个股列表，按净额降序。 */
    List<TopListDO> selectByTradeDate(@Param("tradeDate") String tradeDate);

    /** 取 top_list 表中最新的交易日。 */
    String selectLatestTradeDate();

    int countInvalidAmount(@Param("startDate") String startDate);

    int countInvalidPctChange(@Param("startDate") String startDate);

    int countNetAmountInconsistency(@Param("startDate") String startDate);
}
