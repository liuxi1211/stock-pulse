package com.arthur.stock.mapper;

import com.arthur.stock.model.SwIndustryDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 申万行业分类数据访问层，基于 MyBatis-Plus BaseMapper 提供对 sw_industry 表的 CRUD 操作。
 */
@Mapper
public interface SwIndustryMapper extends BaseMapper<SwIndustryDO> {

    /**
     * 按层级查询行业列表。
     *
     * @param level 行业层级（1/2/3）
     * @return 该层级所有行业
     */
    @Select("SELECT index_code, index_name, level, parent_code, src FROM sw_industry WHERE level = #{level}")
    List<SwIndustryDO> selectByLevel(@Param("level") int level);
}
