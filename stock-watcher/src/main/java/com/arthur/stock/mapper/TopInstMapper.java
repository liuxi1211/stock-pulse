package com.arthur.stock.mapper;

import com.arthur.stock.model.TopInstDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 龙虎榜营业部席位明细数据访问层（top_inst 表）。
 * <p>
 * 主键：(trade_date, ts_code, exalter, side)
 */
@Mapper
public interface TopInstMapper extends BaseMapper<TopInstDO> {

    /** 按 (trade_date, ts_code, exalter, side) 批量删除。 */
    int deleteBatchByKeys(@Param("list") List<TopInstDO> list);

    /** 批量插入。 */
    int insertBatch(@Param("list") List<TopInstDO> list);

    /** 查某股票某交易日的席位明细，按方向、净买入额降序。 */
    List<TopInstDO> selectByTradeDateAndCode(@Param("tradeDate") String tradeDate,
                                              @Param("tsCode") String tsCode);

    /** 查某交易日的知名游资席位明细（按关键词模糊匹配 exalter）。 */
    List<TopInstDO> selectNotableByTradeDate(@Param("tradeDate") String tradeDate,
                                              @Param("keywords") List<String> keywords);

    String selectMaxTradeDate();

    int countInvalidAmount(@Param("startDate") String startDate);

    int countSideAmountInconsistency(@Param("startDate") String startDate);

    int countMissingInToplist(@Param("startDate") String startDate);
}
