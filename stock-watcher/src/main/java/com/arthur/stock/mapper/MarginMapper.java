package com.arthur.stock.mapper;

import com.arthur.stock.model.MarginDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 融资融券汇总数据访问层（margin 表）。
 */
@Mapper
public interface MarginMapper extends BaseMapper<MarginDO> {

    /** 批量 UPSERT（按 exchange_id + trade_date 去重，方言通用：先删后插）。 */
    int deleteBatchByKeys(@Param("list") List<MarginDO> list);

    int insertBatch(@Param("list") List<MarginDO> list);

    /**
     * 查询融资融券汇总趋势。
     * <p>
     * startDate/endDate 为 yyyyMMdd 格式，任一为 null 表示该侧不设限。
     * exchangeId 为 null 或 "ALL" 时不按交易所过滤，查全部。
     * 结果按 trade_date 升序。
     */
    List<MarginDO> selectTrend(@Param("startDate") String startDate,
                              @Param("endDate") String endDate,
                              @Param("exchangeId") String exchangeId);

    /**
     * 取 margin 表中最新的交易日（SELECT MAX(trade_date)）。
     *
     * @return 最新交易日 yyyyMMdd；表为空时返回 null
     */
    String selectLatestTradeDate();
}
