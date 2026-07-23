package com.arthur.stock.service;

import com.arthur.stock.dto.tushare.DailyQuoteDTO;
import com.arthur.stock.model.DailyQuoteDO;

import java.util.List;
import java.util.Map;

/**
 * 日线行情服务
 */
public interface DailyQuoteService {

    /**
     * 按股票代码和日期范围查询日线行情
     *
     * @param tsCode    股票代码，如 000001.SZ
     * @param startDate 开始日期，格式 yyyyMMdd
     * @param endDate   结束日期，格式 yyyyMMdd
     */
    List<DailyQuoteDTO> queryByCodeAndDateRange(String tsCode, String startDate, String endDate);

    /**
     * 按交易日期查询全市场日线行情
     *
     * @param tradeDate 交易日期，格式 yyyyMMdd
     */
    List<DailyQuoteDTO> queryByTradeDate(String tradeDate);

    /**
     * 拉取某只股票的日线行情并保存到数据库。
     * 首次拉取最近30年数据，后续只拉取增量部分。
     *
     * @param tsCode 股票代码，如 000001.SZ
     */
    List<DailyQuoteDTO> fetchAndSaveDailyQuotes(String tsCode);

    /**
     * 拉取某只股票的日线行情并保存到数据库（带已知最新日期，避免 N+1 查询）。
     * 当调用方已持有全量最新日期映射时使用，省去每只股票的单独查询。
     *
     * @param tsCode          股票代码
     * @param knownLastDate   已知的该股票最新交易日期（yyyyMMdd），可为 null（表示无数据）
     */
    List<DailyQuoteDTO> fetchAndSaveDailyQuotes(String tsCode, String knownLastDate);

    /**
     * 按交易日期拉取全市场日线行情并保存到数据库
     *
     * @param tradeDate 交易日期，格式 yyyyMMdd
     */
    List<DailyQuoteDTO> fetchAndSaveByTradeDate(String tradeDate);

    /**
     * 查询本地数据库中指定日期范围内每个交易日有多少只股票的数据
     *
     * @param startDate 开始日期，格式 yyyyMMdd
     * @param endDate   结束日期，格式 yyyyMMdd
     * @return key=交易日期, value=该日有数据的股票数量
     */
    Map<String, Integer> getTradeDateStockCounts(String startDate, String endDate);

    /**
     * 从本地数据库查询指定股票的全部日线数据（按日期升序）
     *
     * @param tsCode 股票代码，如 000001.SZ
     */
    List<DailyQuoteDO> queryLocalByTsCode(String tsCode);

    /**
     * 批量取多只股票末 N 个交易日的 OHLCV（按 ts_code 升序分组，每组取末 recentBars 根，已裁剪）。
     * <p>
     * 替代选股中心旧逻辑里逐股 {@link #queryLocalByTsCode} 全量查询再截断的 N+1 写法：
     * 内部一次性 {@code IN} 查询 + 内存分组裁剪，显著降低 DB 往返与传输量。
     *
     * @param codes      股票代码列表
     * @param recentBars 末尾交易日数（如 60）
     * @return ts_code -&gt; 该股末 recentBars 根 OHLCV（升序）
     */
    Map<String, List<DailyQuoteDO>> queryRecentOhlcvByCodes(List<String> codes, int recentBars);
}
