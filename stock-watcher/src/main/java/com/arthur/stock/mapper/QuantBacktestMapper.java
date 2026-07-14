package com.arthur.stock.mapper;

import com.arthur.stock.model.QuantBacktestDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 回测主表数据访问层，基于 MyBatis-Plus BaseMapper 提供对 quant_backtest 表的 CRUD 操作。
 */
public interface QuantBacktestMapper extends BaseMapper<QuantBacktestDO> {
}
