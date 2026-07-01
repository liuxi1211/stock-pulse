package com.arthur.stock.mapper;

import com.arthur.stock.model.DividendDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分红送股数据访问层，基于MyBatis-Plus BaseMapper提供对dividend表的CRUD操作
 */
@Mapper
public interface DividendMapper extends BaseMapper<DividendDO> {

    @Insert("<script>" +
            "INSERT OR IGNORE INTO dividend (ts_code, end_date, ann_date, div_proc, stk_div, stk_bo_rate, stk_co_rate, " +
            "cash_div, cash_div_tax, record_date, ex_date, pay_date, div_listdate, imp_ann_date, base_date, base_share) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.tsCode}, #{item.endDate}, #{item.annDate}, #{item.divProc}, #{item.stkDiv}, #{item.stkBoRate}, " +
            "#{item.stkCoRate}, #{item.cashDiv}, #{item.cashDivTax}, #{item.recordDate}, #{item.exDate}, #{item.payDate}, " +
            "#{item.divListdate}, #{item.impAnnDate}, #{item.baseDate}, #{item.baseShare})" +
            "</foreach>" +
            "</script>")
    int insertOrIgnoreBatch(@Param("list") List<DividendDO> list);
}