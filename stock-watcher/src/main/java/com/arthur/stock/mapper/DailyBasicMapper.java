package com.arthur.stock.mapper;

import com.arthur.stock.model.DailyBasicDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 每日基本面数据访问层（daily_basic 表）。
 */
@Mapper
public interface DailyBasicMapper extends BaseMapper<DailyBasicDO> {

    /** 批量 UPSERT（按 trade_date + ts_code 去重，方言通用：先删���插）。 */
    int deleteBatchByKeys(@Param("list") List<DailyBasicDO> list);

    int insertBatch(@Param("list") List<DailyBasicDO> list);

    /** 查某股票某交易日的估值。 */
    DailyBasicDO selectByCodeAndDate(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);

    /**
     * 查某股票在指定交易日区间内的基本面数据（含估值/换手率/市值）。
     * <p>
     * startDate/endDate 为 yyyyMMdd 格式，任一为 null 表示该侧不设限（全量）。
     * 结果按 trade_date 升序，供 buildKlineData 按 trade_date join 到 K 线。
     */
    List<DailyBasicDO> selectByCodeAndDateRange(@Param("tsCode") String tsCode,
                                                @Param("startDate") String startDate,
                                                @Param("endDate") String endDate);

    /**
     * 取 daily_basic 表中最新的交易日（SELECT MAX(trade_date)）。
     * <p>
     * 用于行情中心等场景与 daily_quote 对齐取最新有基本面数据的交易日。
     *
     * @return 最新交易日 yyyyMMdd；表为空时返回 null
     */
    String selectLatestTradeDate();

    /**
     * 批量取多只股票在某交易日的基本面数据（估值/换手率/市值）。
     * <p>
     * 主要供 MarketService 等场景 JOIN daily_quote 后补齐 total_mv/pe_ttm/turnover_rate 等字段。
     *
     * @param tsCodes   股票代码列表
     * @param tradeDate 交易日 yyyyMMdd
     * @return 命中记录（无序）；tsCodes 为空时返回空列表
     */
    List<DailyBasicDO> selectByCodesAndDate(@Param("tsCodes") List<String> tsCodes,
                                             @Param("tradeDate") String tradeDate);
}
