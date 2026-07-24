package com.arthur.stock.mapper;

import com.arthur.stock.model.DividendDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 分红送股数据访问层，基于MyBatis-Plus BaseMapper提供对dividend表的CRUD操作
 */
@Mapper
public interface DividendMapper extends BaseMapper<DividendDO> {

    int insertBatch(@Param("list") List<DividendDO> list);

    int deleteBatchByKeys(@Param("list") List<DividendDO> list);

    String selectMaxAnnDate();

    /** 一次性查出所有股票的最新公告日期（ts_code -> latest_ann_date），用于增量更新预加载 */
    List<Map<String, Object>> selectMaxAnnDatePerStock();

    int countInvalidCashDiv();

    int countInvalidStockDiv();

    int countDateLogicErrors();
}
