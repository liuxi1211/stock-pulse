package com.arthur.stock.mapper;

import com.arthur.stock.model.UserDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户数据访问层，基于MyBatis-Plus BaseMapper提供对sys_user表的CRUD操作
 */
@Mapper
public interface UserMapper extends BaseMapper<UserDO> {
}
