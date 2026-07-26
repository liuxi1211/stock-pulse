package com.arthur.stock.mapper;

import com.arthur.stock.model.AdjFactorDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 复权因子数据访问层，基于MyBatis-Plus BaseMapper提供对adj_factor表的CRUD操作
 */
@Mapper
public interface AdjFactorMapper extends BaseMapper<AdjFactorDO> {

    int insertBatch(@Param("list") List<AdjFactorDO> list);

    int deleteBatchByKeys(@Param("list") List<AdjFactorDO> list);

    /**
     * 一次性查出所有股票的最新交易日期（ts_code -> latest_trade_date）。
     * 用于增量更新前预加载，避免逐只股票 N+1 查询。
     */
    List<Map<String, Object>> selectLatestDatePerStock();

    String selectLatestTradeDate();

    /**
     * 统计最近 N 天内关键字段为 NULL 或复权因子 <= 0 的异常记录数。
     * 用于检测数据拉取/写入过程中是否有脏数据。
     */
    int countNullInvalidRecords(@Param("startDate") String startDate);

    /**
     * 统计 adj_factor 表中 distinct 股票数量。
     * 用于计算股票覆盖度。
     */
    int countDistinctStocks();

    /**
     * 批量查询一组股票的复权因子实际记录数。
     * 用于单只股票完整性抽样检测：actual_count vs expected_count。
     *
     * @param tsCodes  股票代码列表
     * @param startDate 起始日期（yyyyMMdd，含），通常为上市日期
     * @param endDate   结束日期（yyyyMMdd，含），通常为最新交易日或退市日期
     * @return [{ts_code, cnt}, ...] 仅有记录的股票会返回
     */
    List<Map<String, Object>> countByTsCodesInRange(
            @Param("tsCodes") List<String> tsCodes,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate);
}
