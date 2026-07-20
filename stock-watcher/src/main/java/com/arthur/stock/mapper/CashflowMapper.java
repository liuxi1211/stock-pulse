package com.arthur.stock.mapper;

import com.arthur.stock.model.CashflowDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 现金流量表数据访问层（cashflow 表）。
 */
@Mapper
public interface CashflowMapper extends BaseMapper<CashflowDO> {

    /** 批量插入（INSERT OR IGNORE 等效语义，方言通用：先按主键批量删除再插入） */
    int insertBatch(@Param("list") List<CashflowDO> list);

    /** 按 (ts_code, end_date, report_type) 批量删除 */
    int deleteBatchByKeys(@Param("list") List<CashflowDO> list);

    /**
     * 取某股票「在 tradeDate 当日已公告」的最近一期合并报表现金流量表
     * （ann_date <= tradeDate 且非空，report_type='1'，按 end_date 降序）。
     * 用于选股时取最新可用财报数据，避免 lookahead bias；锁定合并报表口径保证结果可复现。
     */
    CashflowDO selectLatestAnnouncedBefore(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);
}
