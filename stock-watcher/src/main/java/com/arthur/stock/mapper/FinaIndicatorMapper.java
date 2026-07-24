package com.arthur.stock.mapper;

import com.arthur.stock.model.FinaIndicatorDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 财务指标数据访问层（fina_indicator 表）。
 */
@Mapper
public interface FinaIndicatorMapper extends BaseMapper<FinaIndicatorDO> {

    int insertBatch(@Param("list") List<FinaIndicatorDO> list);

    int deleteBatchByKeys(@Param("list") List<FinaIndicatorDO> list);

    /**
     * 取某股票「在 tradeDate 当日已公告」的最近一期财务指标（ann_date &lt;= tradeDate，按 end_date 降序）。
     * 用于选股时取最新可用财报数据。
     */
    FinaIndicatorDO selectLatestAnnouncedBefore(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);

    // ==================== 数据管控检查 ====================

    /** 取表中最大的 ann_date */
    String selectMaxAnnDate();

    /** 一次性查出所有股票的最新公告日期（ts_code -> latest_ann_date），用于增量更新预加载 */
    List<Map<String, Object>> selectMaxAnnDatePerStock();

    /** 取表中最大的 end_date（最新报告期） */
    String selectMaxEndDate();

    /** 统计指定报告期 ROE 和 ROA 都为空的记录数 */
    int countNullRoeRoa(@Param("endDate") String endDate);

    /** 统计指定报告期资产负债率异常的记录数（< 0 或 > 100） */
    int countInvalidDebtRatio(@Param("endDate") String endDate);
}
