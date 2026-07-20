package com.arthur.stock.mapper;

import com.arthur.stock.model.ExpressDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 业绩快报数据访问层（express 表）。
 */
@Mapper
public interface ExpressMapper extends BaseMapper<ExpressDO> {

    /** 批量插入（方言通用：先按主键批量删除再插入，等效 INSERT OR REPLACE） */
    int insertBatch(@Param("list") List<ExpressDO> list);

    /** 按 (ts_code, end_date) 批量删除（一个报告期一条快报） */
    int deleteBatchByKeys(@Param("list") List<ExpressDO> list);

    /**
     * 取某股票「在 tradeDate 当日已公告」的最近一期业绩快报
     * （ann_date <= tradeDate 且非空，按 end_date 降序 LIMIT 1）。
     * 用于选股时取最新可用业绩快报数据，避免 lookahead bias。
     */
    ExpressDO selectLatestAnnouncedBefore(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);
}
