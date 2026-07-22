package com.arthur.stock.mapper;

import com.arthur.stock.model.WatchlistGroupDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 自选股分组数据访问层，基于MyBatis-Plus BaseMapper提供对sys_watchlist_group表的CRUD操作
 */
@Mapper
public interface WatchlistGroupMapper extends BaseMapper<WatchlistGroupDO> {
}
