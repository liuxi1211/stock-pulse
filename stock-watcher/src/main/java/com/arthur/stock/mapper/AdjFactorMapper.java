package com.arthur.stock.mapper;

import com.arthur.stock.model.AdjFactorDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 复权因子数据访问层，基于MyBatis-Plus BaseMapper提供对adj_factor表的CRUD操作
 */
@Mapper
public interface AdjFactorMapper extends BaseMapper<AdjFactorDO> {

    @Insert("<script>" +
            "INSERT OR IGNORE INTO adj_factor (ts_code, trade_date, adj_factor) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.tsCode}, #{item.tradeDate}, #{item.adjFactor})" +
            "</foreach>" +
            "</script>")
    int insertOrIgnoreBatch(@Param("list") List<AdjFactorDO> list);
}