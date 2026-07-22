package com.arthur.stock.mapper;

import com.arthur.stock.model.StockNamechangeDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 股票更名历史数据访问层，基于 MyBatis-Plus BaseMapper 提供对 stock_namechange 表的 CRUD 操作。
 * <p>
 * 关键查询：point-in-time 取某交易日生效的股票名称（用于回测判定 ST 状态，避免 lookahead bias）。
 */
@Mapper
public interface StockNamechangeMapper extends BaseMapper<StockNamechangeDO> {

    /**
     * 批量插入更名记录。
     */
    int insertBatch(@Param("list") List<StockNamechangeDO> list);

    /**
     * 按 (ts_code, start_date) 批量删除。
     */
    int deleteBatchByKeys(@Param("list") List<StockNamechangeDO> list);

    /**
     * point-in-time 查询：取该股票在 tradeDate 生效的最新 name。
     * <p>
     * 条件：start_date <= trade_date 且 (end_date 为空 或 end_date >= trade_date)，按 start_date 倒序取首条。
     *
     * @param tsCode    股票代码
     * @param tradeDate 交易日 yyyyMMdd
     * @return 该日生效的更名记录，无则 null
     */
    @Select("SELECT ts_code, name, start_date, end_date, change_reason FROM stock_namechange "
            + "WHERE ts_code = #{tsCode} AND start_date <= #{tradeDate} "
            + "AND (end_date IS NULL OR end_date = '' OR end_date >= #{tradeDate}) "
            + "ORDER BY start_date DESC LIMIT 1")
    StockNamechangeDO selectNameAt(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);

    /**
     * 批量取多只股票的全部更名记录（供 buildKlineData 内存判定 ST 状态）。
     *
     * @param tsCodes 股票代���列表
     * @return 更名记录列表（按 ts_code、start_date 升序）
     */
    @Select("<script>"
            + "SELECT ts_code, name, start_date, end_date, change_reason FROM stock_namechange "
            + "WHERE ts_code IN "
            + "<foreach item='c' collection='tsCodes' open='(' separator=',' close=')'>#{c}</foreach> "
            + "ORDER BY ts_code, start_date"
            + "</script>")
    List<StockNamechangeDO> selectByTsCodes(@Param("tsCodes") List<String> tsCodes);
}
