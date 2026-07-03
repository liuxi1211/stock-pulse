package com.arthur.stock.mapper;

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

    int deleteBatchByKeys(@Param("list") List<TradeCalDO> list);
}
