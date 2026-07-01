package com.arthur.stock.mapper;

import com.arthur.stock.model.WatchlistItemDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 关注列表数据访问层，基于MyBatis-Plus BaseMapper提供对sys_watchlist表的CRUD操作
 */
public interface WatchlistMapper extends BaseMapper<WatchlistItemDO> {
}
