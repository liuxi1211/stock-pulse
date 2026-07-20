package com.arthur.stock.mapper;

import com.arthur.stock.model.IncomeDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 利润表数据访问层（income 表）。
 */
@Mapper
public interface IncomeMapper extends BaseMapper<IncomeDO> {

    /** 批量插入（INSERT OR IGNORE 等效语义，方言通用：先按主键批量删除再插入） */
    int insertBatch(@Param("list") List<IncomeDO> list);

    /** 按 (ts_code, end_date, report_type) 批量删除 */
    int deleteBatchByKeys(@Param("list") List<IncomeDO> list);

    /**
     * 取某股票「在 tradeDate 当日已公告」的最近一期合并报表利润表
     * （ann_date <= tradeDate 且非空，report_type='1'，按 end_date 降序）。
     * 用于选股时取最新可用财报数据，避免 lookahead bias；锁定合并报表口径保证结果可复现。
     */
    IncomeDO selectLatestAnnouncedBefore(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);
}
