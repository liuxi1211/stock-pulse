package com.arthur.stock.mapper;

import com.arthur.stock.model.StockBasicDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 股票基础信息数据访问层，基于MyBatis-Plus BaseMapper提供对stock_basic表的CRUD操作
 */
@Mapper
public interface StockBasicMapper extends BaseMapper<StockBasicDO> {

    int insertBatch(@Param("list") List<StockBasicDO> list);

    int deleteBatchByKeys(@Param("list") List<StockBasicDO> list);
}
