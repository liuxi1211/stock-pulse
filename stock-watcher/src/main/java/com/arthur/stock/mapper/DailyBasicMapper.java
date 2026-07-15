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
}
