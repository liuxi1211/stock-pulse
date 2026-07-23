package com.arthur.stock.mapper;

import com.arthur.stock.model.IndexWeightDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 指数成分股权重数据访问层，基于MyBatis-Plus BaseMapper提供对index_weight表的CRUD操作
 */
@Mapper
public interface IndexWeightMapper extends BaseMapper<IndexWeightDO> {

    /**
     * 批量插入指数成分股权重。
     */
    int insertBatch(@Param("list") List<IndexWeightDO> list);

    /**
     * 取该指数最新交易日的成分股代码列表（实时选股用）。
     */
    @Select("SELECT con_code FROM index_weight WHERE ts_code = #{tsCode} "
            + "AND trade_date = (SELECT MAX(trade_date) FROM index_weight WHERE ts_code = #{tsCode})")
    List<String> selectLatestConstituents(@Param("tsCode") String tsCode);

    /**
     * 取该指数 ≤ 指定日期的最新快照的成分股代码列表（回测防幸存者偏差用）。
     */
    @Select("SELECT con_code FROM index_weight WHERE ts_code = #{tsCode} "
            + "AND trade_date = (SELECT MAX(trade_date) FROM index_weight "
            + "WHERE ts_code = #{tsCode} AND trade_date <= #{tradeDate})")
    List<String> selectConstituentsAt(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);

    /**
     * 取该指数 ≤ 指定日期的最新生效交易日（即上述快照的 trade_date）。
     * 早于最早快照时返回 null，调用方据此判定是否告警。
     */
    @Select("SELECT MAX(trade_date) FROM index_weight "
            + "WHERE ts_code = #{tsCode} AND trade_date <= #{tradeDate}")
    String selectEffectiveDate(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);

    /**
     * 取该指数在指定日期区间内所有曾入选的成分股代码并集（回测防幸存者偏差，避免遗漏调样前后标的）。
     * startDate/endDate 为 null 时不加该侧边界。
     */
    @Select("<script>"
            + "SELECT DISTINCT con_code FROM index_weight WHERE ts_code = #{tsCode}"
            + "<if test='startDate != null and startDate != \"\"'> AND trade_date &gt;= #{startDate}</if>"
            + "<if test='endDate != null and endDate != \"\"'> AND trade_date &lt;= #{endDate}</if>"
            + "</script>")
    List<String> selectConstituentsInRange(@Param("tsCode") String tsCode,
                                           @Param("startDate") String startDate,
                                           @Param("endDate") String endDate);

    // ==================== 数据管控检查 ====================

    /**
     * 取表中最新的 trade_date。
     */
    String selectLatestTradeDate();

    /**
     * 按指数代码统计最新交易日的成分股数量。
     */
    List<Map<String, Object>> countByIndexCode(@Param("tradeDate") String tradeDate);

    /**
     * 统计最新一期权重总和不在 99-101 之间的指数数量。
     */
    int countWeightSumAbnormal(@Param("tradeDate") String tradeDate);

    /**
     * 统计最新一期权重无效（weight <= 0 OR weight > 20）的记录数。
     */
    int countInvalidWeight(@Param("tradeDate") String tradeDate);
}
