package com.arthur.stock.mapper;

import com.arthur.stock.model.ForecastDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 业绩预告数据访问层（forecast 表）。
 */
@Mapper
public interface ForecastMapper extends BaseMapper<ForecastDO> {

    /** 批量插入（方言通用：先按主键批量删除再插入，等效 INSERT OR REPLACE） */
    int insertBatch(@Param("list") List<ForecastDO> list);

    /** 按 (ts_code, end_date, ann_date) 批量删除（含 ann_date 以保留多次预告历史） */
    int deleteBatchByKeys(@Param("list") List<ForecastDO> list);

    /**
     * 取某股票「在 tradeDate 当日已公告」的最近一期业绩预告
     * （ann_date <= tradeDate 且非空，按 end_date 降序、ann_date 降序）。
     * 用于选股时取最新可用业绩预告数据，避免 lookahead bias；
     * 同一报告期可能有多条预告（首次+修正），取最新公告日的一条。
     */
    ForecastDO selectLatestAnnouncedBefore(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);

    // ==================== 数据管控检查 ====================

    /** 取表中最大的 ann_date */
    String selectMaxAnnDate();

    /** 一次性查出所有股票的最新公告日期（ts_code -> latest_ann_date），用于增量更新预加载 */
    List<Map<String, Object>> selectMaxAnnDatePerStock();

    /** 统计指定月份的预告记录数 */
    int countByAnnMonth(@Param("annMonth") String annMonth);

    /** 统计范围逻辑错误的记录数：p_change_min > p_change_max 或 net_profit_min > net_profit_max */
    int countRangeLogicErrors();

    /** 统计预告类型与变动方向矛盾的记录数 */
    int countTypeConsistencyErrors();
}
