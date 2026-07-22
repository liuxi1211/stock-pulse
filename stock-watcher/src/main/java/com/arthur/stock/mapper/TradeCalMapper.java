package com.arthur.stock.mapper;

import com.arthur.stock.constant.ExchangeEnum;
import com.arthur.stock.model.TradeCalDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 交易日历数据访问层，基于MyBatis-Plus BaseMapper提供对trade_cal表的CRUD操作
 */
@Mapper
public interface TradeCalMapper extends BaseMapper<TradeCalDO> {

    int insertBatch(@Param("list") List<TradeCalDO> list);

    /**
     * 按 (exchange, cal_date) 批量删除。
     * <p>
     * 注意：为兼容 SQLite（不支持行值 IN 语法），本方法在 Service 层
     * 按 exchange 分组后调用 {@link #deleteByExchangeAndCalDates} 实现，
     * 此处保留方法名作为门面（已废弃，请勿直接使用）。
     *
     * @deprecated 请使用 {@link #deleteByExchangeAndCalDates(ExchangeEnum, List)} 按交易所分组删除
     */
    @Deprecated
    int deleteBatchByKeys(@Param("list") List<TradeCalDO> list);

    /**
     * 按 exchange + cal_date IN 删除，单字段 IN 语法 MySQL/SQLite 双方言通用。
     *
     * @param exchange 交易所枚举
     * @param calDates 日期列表（yyyyMMdd）
     * @return 删除行数
     */
    int deleteByExchangeAndCalDates(@Param("exchange") ExchangeEnum exchange,
                                    @Param("calDates") List<String> calDates);

    /**
     * 按 (exchange, cal_date) 更新 6 个调仓标记字段。
     * 单条更新，供 Service 层循环/批量调用，跨 MySQL/SQLite 通用。
     */
    int updateRebalanceFlags(TradeCalDO entity);

    /**
     * 批量更新 6 个调仓标记字段（CASE WHEN 构造，跨 MySQL/SQLite 通用）。
     */
    int updateRebalanceFlagsBatch(@Param("list") List<TradeCalDO> list);

    // ==================== 数据管控检查 ====================

    /**
     * 统计 SSE/SZSE 在指定日期后 is_open 不一致的记录数
     */
    int countSseSzseInconsistency(@Param("startDate") String startDate);
}
