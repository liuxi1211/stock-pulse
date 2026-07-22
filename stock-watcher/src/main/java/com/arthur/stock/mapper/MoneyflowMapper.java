package com.arthur.stock.mapper;

import com.arthur.stock.model.MoneyflowDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 个股资金流向数据访问层（stock_moneyflow 表）。
 */
@Mapper
public interface MoneyflowMapper extends BaseMapper<MoneyflowDO> {

    /** 批量 UPSERT（按 ts_code + trade_date 去重，方言通用：先删后插）。 */
    int deleteBatchByKeys(@Param("list") List<MoneyflowDO> list);

    int insertBatch(@Param("list") List<MoneyflowDO> list);

    /** TOP N 主力净流入排行。 */
    List<MoneyflowDO> selectTopByTradeDate(@Param("tradeDate") String tradeDate,
                                            @Param("limit") int limit,
                                            @Param("sortBy") String sortBy,
                                            @Param("order") String order);

    /** 单股近 N 日资金流向。 */
    List<MoneyflowDO> selectByCodeAndDateRange(@Param("tsCode") String tsCode,
                                                @Param("startDate") String startDate,
                                                @Param("endDate") String endDate);

    /** 取最新交易日。 */
    String selectLatestTradeDate();
}
