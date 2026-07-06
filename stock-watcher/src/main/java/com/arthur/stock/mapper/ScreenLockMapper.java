package com.arthur.stock.mapper;

import com.arthur.stock.model.ScreenLockDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 多因子选股锁定记录数据访问层，基于MyBatis-Plus BaseMapper提供对screen_lock表的CRUD操作
 */
public interface ScreenLockMapper extends BaseMapper<ScreenLockDO> {
}
