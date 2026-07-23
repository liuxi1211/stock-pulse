package com.arthur.stock.mapper;

import com.arthur.stock.model.DividendDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分红送股数据访问层，基于MyBatis-Plus BaseMapper提供对dividend表的CRUD操作
 */
@Mapper
public interface DividendMapper extends BaseMapper<DividendDO> {

    int insertBatch(@Param("list") List<DividendDO> list);

    int deleteBatchByKeys(@Param("list") List<DividendDO> list);

    String selectMaxAnnDate();

    int countInvalidCashDiv();

    int countInvalidStockDiv();

    int countDateLogicErrors();
}
