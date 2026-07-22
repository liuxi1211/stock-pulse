package com.arthur.stock.mapper;

import com.arthur.stock.model.IndexDailyDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 指数日线行情数据访问层，基于MyBatis-Plus BaseMapper提供对index_daily表的CRUD操作
 */
@Mapper
public interface IndexDailyMapper extends BaseMapper<IndexDailyDO> {

    /**
     * 批量插入指数日线行情（UPSERT 语义由 service 层先删后插实现，跨 SQLite/MySQL 通用）
     */
    int insertBatch(@Param("list") List<IndexDailyDO> list);

    /**
     * 按 (ts_code, trade_date) 批量删除
     */
    int deleteBatchByKeys(@Param("list") List<IndexDailyDO> list);

    /**
     * 取表中最新的 trade_date（用于查询最新交易日数据）
     */
    String selectLatestTradeDate();

    /**
     * 按多指数代码 + 交易日查询
     */
    List<IndexDailyDO> selectByCodesAndTradeDate(@Param("codes") List<String> codes,
                                                 @Param("tradeDate") String tradeDate);

    /**
     * 取给定指数代码的最新交易日数据（子查询 MAX(trade_date)）
     */
    List<IndexDailyDO> selectLatestByCodes(@Param("codes") List<String> codes);

    /**
     * 取单个指数的近 N 个交易日行情（按 trade_date DESC）
     */
    List<IndexDailyDO> selectByCodeOrderByTradeDate(@Param("tsCode") String tsCode,
                                                     @Param("limit") int limit);

    // ==================== 数据管控检查 ====================

    /**
     * 查询指定日期起缺失的核心指数代码列表
     */
    List<String> selectMissingCoreIndices(@Param("startDate") String startDate);

    /**
     * 统计指定日期起价格异常记录数（high &lt; low OR close &lt;= 0 OR open &lt;= 0）
     */
    int countPriceAnomalies(@Param("startDate") String startDate);
}
