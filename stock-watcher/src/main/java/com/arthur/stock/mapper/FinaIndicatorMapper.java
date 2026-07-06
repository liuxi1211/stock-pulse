package com.arthur.stock.mapper;

import com.arthur.stock.model.FinaIndicatorDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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
}
