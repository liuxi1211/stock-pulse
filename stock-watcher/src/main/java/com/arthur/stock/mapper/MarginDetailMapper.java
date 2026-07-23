package com.arthur.stock.mapper;

import com.arthur.stock.model.MarginDetailDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 融资融券个股明细数据访问层（margin_detail 表）。
 */
@Mapper
public interface MarginDetailMapper extends BaseMapper<MarginDetailDO> {

    /** 批量 UPSERT（按 trade_date + ts_code 去重，方言通用：先删后插）。 */
    int deleteBatchByKeys(@Param("list") List<MarginDetailDO> list);

    int insertBatch(@Param("list") List<MarginDetailDO> list);

    /**
     * 查询某交易日融资融券个股明细 Top-N。
     * <p>
     * sortBy 白名单：rzrqye / rzye / rqye / rzmre。
     * order 仅接受 asc / desc。
     */
    List<MarginDetailDO> selectTopByTradeDate(@Param("tradeDate") String tradeDate,
                                              @Param("limit") int limit,
                                              @Param("sortBy") String sortBy,
                                              @Param("order") String order);

    /**
     * 取 margin_detail 表中最新的交易日（SELECT MAX(trade_date)）。
     *
     * @return 最新交易日 yyyyMMdd；表为空时返回 null
     */
    String selectLatestTradeDate();

    // ==================== 数据管控检查 ====================

    /**
     * 统计最近30天余额无效（rzye < 0 OR rqye < 0）的记录数。
     */
    int countInvalidBalance(@Param("startDate") String startDate);
}
