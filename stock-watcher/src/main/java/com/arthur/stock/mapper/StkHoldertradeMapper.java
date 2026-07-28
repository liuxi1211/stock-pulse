package com.arthur.stock.mapper;

import com.arthur.stock.model.StkHoldertradeDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StkHoldertradeMapper extends BaseMapper<StkHoldertradeDO> {

    int deleteBatchByKeys(@Param("list") List<StkHoldertradeDO> list);

    int insertBatch(@Param("list") List<StkHoldertradeDO> list);

    List<StkHoldertradeDO> selectByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                                       @Param("startDate") String startDate,
                                                       @Param("endDate") String endDate);

    String selectMaxAnnDate();

    int countInvalidChangeVol();

    int countMissingInDe();
}
