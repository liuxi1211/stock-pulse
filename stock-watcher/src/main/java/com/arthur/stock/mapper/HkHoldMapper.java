package com.arthur.stock.mapper;

import com.arthur.stock.dto.HkHoldTrendVO;
import com.arthur.stock.model.HkHoldDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 沪深港通持股明细数据访问层（hk_hold 表）。
 * <p>
 * 主键：(trade_date, code)。
 */
@Mapper
public interface HkHoldMapper extends BaseMapper<HkHoldDO> {

    /** 批量删除（按 trade_date + code 主键）。 */
    int deleteBatchByKeys(@Param("list") List<HkHoldDO> list);

    /** 批量插入。 */
    int insertBatch(@Param("list") List<HkHoldDO> list);

    /**
     * 查询持股占比趋势（按 trade_date + exchange_id 汇总 SUM(ratio)）。
     *
     * @param startDate  起始日期 yyyyMMdd，为 null/空 时不设下限
     * @param endDate    结束日期 yyyyMMdd，为 null/空 时不设上限
     * @param exchangeId 交易所代码 SH/SZ，为 null/空 时不过滤
     */
    List<HkHoldTrendVO> selectRatioTrend(@Param("startDate") String startDate,
                                        @Param("endDate") String endDate,
                                        @Param("exchangeId") String exchangeId);

    /**
     * 查询某交易日持股数量 Top-N。
     *
     * @param tradeDate  交易日 yyyyMMdd
     * @param exchangeId 交易所代码 SH/SZ，为 null/空/ALL 时不过滤
     * @param limit      返回条数
     */
    List<HkHoldDO> selectTopHoldings(@Param("tradeDate") String tradeDate,
                                     @Param("exchangeId") String exchangeId,
                                     @Param("limit") int limit);

    /**
     * 查某股票在指定交易日区间内的持股明细（按 trade_date 升序）。
     *
     * @param tsCode    股票代码，如 000001.SZ
     * @param startDate 开始日期 yyyyMMdd，可为 null
     * @param endDate   结束日期 yyyyMMdd，可为 null
     */
    List<HkHoldDO> selectByCodeAndDateRange(@Param("tsCode") String tsCode,
                                            @Param("startDate") String startDate,
                                            @Param("endDate") String endDate);

    /**
     * 取 hk_hold 表中最新的交易日（SELECT MAX(trade_date)）。
     *
     * @return 最新交易日 yyyyMMdd；表为空时返回 null
     */
    String selectLatestTradeDate();

    // ==================== 数据管控检查 ====================

    /**
     * 统计最近30天 vol < 0 的记录数。
     */
    int countInvalidVol(@Param("startDate") String startDate);

    /**
     * 统计最近30天 ratio < 0 OR ratio > 30 的记录数。
     */
    int countInvalidRatio(@Param("startDate") String startDate);
}
