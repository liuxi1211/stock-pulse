package com.arthur.stock.mapper;

import com.arthur.stock.model.StockBasicDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 股票基础信息数据访问层，基于MyBatis-Plus BaseMapper提供对stock_basic表的CRUD操作
 */
@Mapper
public interface StockBasicMapper extends BaseMapper<StockBasicDO> {

    @Insert("<script>" +
            "INSERT OR REPLACE INTO stock_basic (ts_code, symbol, name, area, industry, fullname, enname, cnspell, " +
            "market, exchange, curr_type, list_status, list_date, delist_date, is_hs, act_name, act_ent_type) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.tsCode}, #{item.symbol}, #{item.name}, #{item.area}, #{item.industry}, #{item.fullname}, " +
            "#{item.enname}, #{item.cnspell}, #{item.market}, #{item.exchange}, #{item.currType}, #{item.listStatus}, " +
            "#{item.listDate}, #{item.delistDate}, #{item.isHs}, #{item.actName}, #{item.actEntType})" +
            "</foreach>" +
            "</script>")
    int insertOrReplaceBatch(@Param("list") List<StockBasicDO> list);
}