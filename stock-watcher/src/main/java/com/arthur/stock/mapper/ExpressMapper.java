package com.arthur.stock.mapper;

import com.arthur.stock.model.ExpressDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 业绩快报数据访问层（express 表）。
 */
@Mapper
public interface ExpressMapper extends BaseMapper<ExpressDO> {

    /** 批量插入（方言通用：先按主键批量删除再插入，等效 INSERT OR REPLACE） */
    int insertBatch(@Param("list") List<ExpressDO> list);

    /** 按 (ts_code, end_date) 批量删除（一个报告期一条快报） */
    int deleteBatchByKeys(@Param("list") List<ExpressDO> list);

    /**
     * 取某股票「在 tradeDate 当日已公告」的最近一期业绩快报
     * （ann_date <= tradeDate 且非空，按 end_date 降序 LIMIT 1）。
     * 用于选股时取最新可用业绩快报数据，避免 lookahead bias。
     */
    ExpressDO selectLatestAnnouncedBefore(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);

    // ==================== 数据管控检查 ====================

    /** 取表中最大的 ann_date */
    String selectMaxAnnDate();

    /** 一次性查出所有股票的最新公告日期（ts_code -> latest_ann_date），用于增量更新预加载 */
    List<Map<String, Object>> selectMaxAnnDatePerStock();

    /** 取表中最大的 end_date（最新报告期） */
    String selectMaxEndDate();

    /** 统计指定报告期营收或总资产为负的记录数 */
    int countInvalidRevenueAssets(@Param("endDate") String endDate);

    /** 统计增长一致性错误的记录数：正增长但净利润更少 */
    int countGrowthConsistencyErrors();
}
