package com.arthur.stock.mapper;

import com.arthur.stock.model.AdjFactorDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 复权因子数据访问层，基于MyBatis-Plus BaseMapper提供对adj_factor表的CRUD操作
 */
@Mapper
public interface AdjFactorMapper extends BaseMapper<AdjFactorDO> {

    int insertBatch(@Param("list") List<AdjFactorDO> list);

    int deleteBatchByKeys(@Param("list") List<AdjFactorDO> list);
}
