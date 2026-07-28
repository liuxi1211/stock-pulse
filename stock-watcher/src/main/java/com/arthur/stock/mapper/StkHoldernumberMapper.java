package com.arthur.stock.mapper;

import com.arthur.stock.model.StkHoldernumberDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StkHoldernumberMapper extends BaseMapper<StkHoldernumberDO> {

    int insertBatch(@Param("list") List<StkHoldernumberDO> list);

    int deleteBatchByKeys(@Param("list") List<StkHoldernumberDO> list);

    List<StkHoldernumberDO> selectRecentByTsCode(@Param("tsCode") String tsCode,
                                                  @Param("limit") int limit);

    String selectMaxAnnDate();

    int countInvalidHolderNum();

    int countMissingEndDate();
}
