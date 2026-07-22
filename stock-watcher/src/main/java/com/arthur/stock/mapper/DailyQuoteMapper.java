package com.arthur.stock.mapper;

import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.vo.StockListDTO;
import com.arthur.stock.vo.StockRankVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 日线行情数据访问层，基于MyBatis-Plus BaseMapper提供对daily_quote表的CRUD操作
 */
@Mapper
public interface DailyQuoteMapper extends BaseMapper<DailyQuoteDO> {

    int insertBatch(@Param("list") List<DailyQuoteDO> list);

    int deleteBatchByKeys(@Param("list") List<DailyQuoteDO> list);

    List<Map<String, Object>> selectTradeDateStockCount(@Param("startDate") String startDate,
                                                         @Param("endDate") String endDate);

    String selectLatestTradeDate();

    List<DailyQuoteDO> selectTopGainers(@Param("tradeDate") String tradeDate, @Param("limit") int limit);

    List<DailyQuoteDO> selectTopLosers(@Param("tradeDate") String tradeDate, @Param("limit") int limit);

    List<DailyQuoteDO> selectTopAmount(@Param("tradeDate") String tradeDate, @Param("limit") int limit);

    /**
     * 换手率排行 TOP N（JOIN daily_basic + stock_basic）。
     * <p>
     * 直接返回 StockRankVO（含 turnoverRate），避免再走 N+1 名称补齐。
     */
    List<StockRankVO> selectTopTurnover(@Param("tradeDate") String tradeDate, @Param("limit") int limit);

    /**
     * 统计指定交易日的上涨家数（pct_chg &gt; 0，仅上市股票）。
     */
    int countUpByTradeDate(@Param("tradeDate") String tradeDate);

    /**
     * 统计指定交易日的下跌家数（pct_chg &lt; 0，仅上市股票）。
     */
    int countDownByTradeDate(@Param("tradeDate") String tradeDate);

    /**
     * 统计指定交易日的平盘家数（pct_chg = 0，仅上市股票）。
     */
    int countFlatByTradeDate(@Param("tradeDate") String tradeDate);

    /**
     * 统计指定交易日的涨停家数（按板块区分涨跌停阈值；ST 股票按 4.9% 阈值）。
     */
    int countLimitUpByTradeDate(@Param("tradeDate") String tradeDate);

    /**
     * 统计指定交易日的跌停家数（按板块区分涨跌停阈值；ST 股票按 4.9% 阈值）。
     */
    int countLimitDownByTradeDate(@Param("tradeDate") String tradeDate);

    /**
     * 市场温度单 SQL 聚合：一次扫描统计涨/跌/平/涨停/跌停家数。
     * <p>
     * 等价于 {@link #countUpByTradeDate} / {@link #countDownByTradeDate} /
     * {@link #countFlatByTradeDate} / {@link #countLimitUpByTradeDate} /
     * {@link #countLimitDownByTradeDate} 五次查询的合并，避免 5 次全表扫描。
     * <p>
     * 阈值规则：主板 ±10%、北交所 ±30%、创业板/科创板 ±20%、ST ±5%。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @return 单行 Map，键：up_count / down_count / flat_count / limit_up_count / limit_down_count；
     *         无数据时各键值为 null
     */
    Map<String, Object> selectMarketTemperature(@Param("tradeDate") String tradeDate);

    /**
     * 批量取多只股票在 [startDate, endDate] 的 OHLCV（按 ts_code、trade_date 升序）。
     * <p>
     * 替代旧逻辑里逐股 {@code queryLocalByTsCode} 全量查询再截断的 N+1 写法。
     * 依赖 daily_quote 主键索引 (ts_code, trade_date)。
     */
    List<DailyQuoteDO> selectOhlcvByCodesAndDateRange(@Param("codes") List<String> codes,
                                                     @Param("startDate") String startDate,
                                                     @Param("endDate") String endDate);

    /**
     * 一次性取多只股票在某交易日的行情（主要用于批量取收盘价）。
     */
    List<DailyQuoteDO> selectByCodesAndTradeDate(@Param("codes") List<String> codes,
                                                 @Param("tradeDate") String tradeDate);

    /**
     * 全市场 distinct trade_date 升序，作为简化交易日历。替换旧 {@code selectList(null)} 全表加载。
     */
    List<String> selectDistinctTradeDatesAsc();

    /**
     * 行情中心股票列表：JOIN daily_basic + stock_basic + sw_industry_member 一次查询取 13 列。
     * <p>
     * 支持 industryCode（申万一级行业 index_code）/market（沪市/深市 按代码后缀，创业板/科创板/北交所 按
     * stock_basic.market）过滤；sortClause 由 Service 层按白名单拼装后以 ${} 注入；分页用 LIMIT/OFFSET。
     *
     * @param tradeDate    交易日 yyyyMMdd
     * @param industryCode 申万一级行业 index_code（可空）
     * @param market       市场过滤值（可空）
     * @param sortClause   已校验的 ORDER BY 片段（Service 白名单拼装，防注入）
     * @param size         每页条数
     * @param offset       偏移量
     */
    List<StockListDTO> selectStockList(@Param("tradeDate") String tradeDate,
                                       @Param("industryCode") String industryCode,
                                       @Param("market") String market,
                                       @Param("sortClause") String sortClause,
                                       @Param("size") int size,
                                       @Param("offset") int offset);

    /**
     * 行情中心股票列表总数（与 {@link #selectStockList} 同 FROM/WHERE，不含排序分页）。
     */
    long selectStockListCount(@Param("tradeDate") String tradeDate,
                              @Param("industryCode") String industryCode,
                              @Param("market") String market);
}
