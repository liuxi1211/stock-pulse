package com.arthur.stock.mapper;

import com.arthur.stock.model.FactorSnapshotDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 因子预计算快照数据访问层（factor_snapshot 表）。
 */
@Mapper
public interface FactorSnapshotMapper extends BaseMapper<FactorSnapshotDO> {

    /** 批量 UPSERT（方言通用：先删后插）。 */
    int deleteBatchByKeys(@Param("list") List<FactorSnapshotDO> list);

    int insertBatch(@Param("list") List<FactorSnapshotDO> list);

    /**
     * 查某交易日某股票的因子快照（不区分 params/outputIndex，返回该日该股全部预计算行）。
     */
    List<FactorSnapshotDO> selectByDateAndCode(@Param("tradeDate") String tradeDate,
                                               @Param("tsCode") String tsCode);

    /**
     * 批量查某交易日多只股票的因子快照（用于选股时一次性取全部候选股的预计算值）。
     */
    List<FactorSnapshotDO> selectByDateAndCodes(@Param("tradeDate") String tradeDate,
                                                @Param("codes") List<String> codes);
}
