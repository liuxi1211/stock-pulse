package com.arthur.stock.mapper;

import com.arthur.stock.dto.BlockTradeWithCloseVO;
import com.arthur.stock.model.BlockTradeDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 大宗交易数据访问层（block_trade 表）。
 */
@Mapper
public interface BlockTradeMapper extends BaseMapper<BlockTradeDO> {

    /** 批量删除（按主键 trade_date + ts_code + buyer + seller）。 */
    int deleteBatchByKeys(@Param("list") List<BlockTradeDO> list);

    /** 批量插入。 */
    int insertBatch(@Param("list") List<BlockTradeDO> list);

    /**
     * 分页查询大宗交易（JOIN daily_quote 取收盘价）。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @param offset    偏移量
     * @param limit     每页条数
     * @param sortBy    排序字段白名单：amount / price / vol
     * @param order     排序方向：asc / desc
     */
    List<BlockTradeWithCloseVO> selectPage(@Param("tradeDate") String tradeDate,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit,
                                           @Param("sortBy") String sortBy,
                                           @Param("order") String order);

    /** 按交易日统计记录数。 */
    int countByTradeDate(@Param("tradeDate") String tradeDate);

    /**
     * 查某交易日全部大宗交易（JOIN daily_quote 取收盘价），供折溢价分布计算。
     * 不分页。
     */
    List<BlockTradeWithCloseVO> selectAllWithCloseByTradeDate(@Param("tradeDate") String tradeDate);

    /** 取 block_trade 表中最新交易日（SELECT MAX(trade_date)）。 */
    String selectLatestTradeDate();

    int countInvalidPriceVol(@Param("startDate") String startDate);

    int countBuyerSellerSame(@Param("startDate") String startDate);

    int countAmountCalculationError(@Param("startDate") String startDate);
}
