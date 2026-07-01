package com.arthur.stock.mapper;

import com.arthur.stock.model.TradeCalDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 交易日历数据访问层，基于MyBatis-Plus BaseMapper提供对trade_cal表的CRUD操作
 */
@Mapper
public interface TradeCalMapper extends BaseMapper<TradeCalDO> {

    @Insert("<script>" +
            "INSERT OR REPLACE INTO trade_cal (exchange, cal_date, is_open, pretrade_date) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.exchange}, #{item.calDate}, #{item.isOpen}, #{item.pretradeDate})" +
            "</foreach>" +
            "</script>")
    int insertOrReplaceBatch(@Param("list") List<TradeCalDO> list);
}