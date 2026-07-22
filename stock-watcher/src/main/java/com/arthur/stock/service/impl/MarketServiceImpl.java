package com.arthur.stock.service.impl;

import com.arthur.stock.constant.IndexConstants;
import com.arthur.stock.constant.RankingType;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.StockListQueryDTO;
import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.mapper.IndexDailyMapper;
import com.arthur.stock.model.IndexDailyDO;
import com.arthur.stock.service.IndexDailyService;
import com.arthur.stock.service.MarketService;
import com.arthur.stock.util.StockDataHelper;
import com.arthur.stock.vo.MarketIndexVO;
import com.arthur.stock.vo.MarketRankingVO;
import com.arthur.stock.vo.MarketTemperatureVO;
import com.arthur.stock.vo.StockListDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 市场行情服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketServiceImpl implements MarketService {

    private static final int RANK_LIMIT = 10;

    /** stock-list 分页默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 50;
    /** stock-list 每页条数上限 */
    private static final int MAX_PAGE_SIZE = 500;

    /** trade_date 存储格式 yyyyMMdd */
    private static final DateTimeFormatter TRADE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 排序字段白名单 -&gt; SQL 列表达式。
     * <p>
     * sortClause 以 ${} 注入 XML（非预编译占位），故必须经此白名单校验，杜绝 SQL 注入。
     * amplitude 为 SELECT 中计算的别名（无表前缀）。
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "pctChg", "dq.pct_chg",
            "vol", "dq.vol",
            "amount", "dq.amount",
            "turnoverRate", "db.turnover_rate",
            "totalMv", "db.total_mv",
            "peTtm", "db.pe_ttm",
            "pb", "db.pb",
            "volumeRatio", "db.volume_ratio",
            "amplitude", "amplitude"
    );

    /**
     * 来自 LEFT JOIN daily_basic 的排序字段，可能为 NULL；NULL 一律排最后（兼容 MySQL/SQLite 的 {@code (col IS NULL)} 写法）。
     */
    private static final Set<String> NULL_LAST_SORT_FIELDS = Set.of(
            "turnoverRate", "totalMv", "peTtm", "pb", "volumeRatio"
    );

    /** market 过滤白名单：沪市/深市 按代码后缀，创业板/科创板/北交所 按 stock_basic.market */
    private static final Set<String> MARKET_WHITELIST = Set.of(
            "沪市", "深市", "创业板", "科创板", "北交所"
    );

    private final DailyQuoteMapper dailyQuoteMapper;
    private final StockDataHelper stockDataHelper;
    private final IndexDailyMapper indexDailyMapper;
    private final IndexDailyService indexDailyService;

    /**
     * 供 {@code @Cacheable} SpEL 调用：取 index_daily 表中最新的交易日。
     * <p>
     * 用作 indices 缓存键，使得每个交易日缓存自动失效。
     */
    public String getLatestTradeDate() {
        String date = indexDailyMapper.selectLatestTradeDate();
        return date != null ? date : "empty";
    }

    /**
     * 供 {@code @Cacheable} SpEL 调用：解析市场温度查询的实际交易日。
     * <p>
     * tradeDate 非空时原样返回；为空时取 daily_quote 最新交易日；表为空返回 "empty"。
     * 用作 marketTemperature 缓存键，使每个交易日缓存自然失效。
     */
    public String resolveTemperatureTradeDate(String tradeDate) {
        if (tradeDate != null && !tradeDate.isEmpty()) {
            return tradeDate;
        }
        String latest = dailyQuoteMapper.selectLatestTradeDate();
        return latest != null ? latest : "empty";
    }

    @Override
    @Cacheable(value = "indices", key = "#root.target.getLatestTradeDate()")
    public List<MarketIndexVO> getMarketIndices() {
        List<IndexDailyDO> idxList = indexDailyService.getLatestByCodes(IndexConstants.DEFAULT_INDEX_CODES);
        if (idxList == null || idxList.isEmpty()) {
            log.warn("getMarketIndices: index_daily 无数据，返回空列表");
            return Collections.emptyList();
        }
        return idxList.stream()
                .map(this::toMarketIndexVO)
                .collect(Collectors.toList());
    }

    private MarketIndexVO toMarketIndexVO(IndexDailyDO idx) {
        String name = IndexConstants.INDEX_NAME_MAP.getOrDefault(idx.getTsCode(), idx.getTsCode());
        // vol 列单位为"万手"(DECIMAL)，四舍五入到整数万手；amount 列单位为"亿元"，保留 2 位小数
        Long volLong = idx.getVol() != null
                ? idx.getVol().setScale(0, RoundingMode.HALF_UP).longValueExact()
                : null;
        String turnoverStr = idx.getAmount() != null
                ? idx.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString() + "亿"
                : null;
        // trade_date 存储格式 yyyyMMdd，转为 yyyy-MM-dd 供前端展示
        String formattedDate = formatTradeDate(idx.getTradeDate());
        return MarketIndexVO.builder()
                .code(idx.getTsCode())
                .name(name)
                .currentPoint(idx.getClose())
                .changeAmount(idx.getChangeValue())
                .changePercent(idx.getPctChg())
                .volume(volLong)
                .turnover(turnoverStr)
                .tradeDate(formattedDate)
                .build();
    }

    /**
     * 将 yyyyMMdd 格式的交易日转为 yyyy-MM-dd；解析失败时原样返回并告警。
     */
    private String formatTradeDate(String rawDate) {
        if (rawDate == null || rawDate.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(rawDate, TRADE_DATE_FMT).toString();
        } catch (DateTimeParseException e) {
            log.warn("无法解析 trade_date: {}，原样返回", rawDate);
            return rawDate;
        }
    }

    @Override
    public MarketRankingVO getMarketRanking() {
        String latestDate = dailyQuoteMapper.selectLatestTradeDate();
        if (latestDate == null) {
            return MarketRankingVO.builder()
                    .topGainers(Collections.emptyList())
                    .topLosers(Collections.emptyList())
                    .topAmount(Collections.emptyList())
                    .topTurnover(Collections.emptyList())
                    .build();
        }

        return MarketRankingVO.builder()
                .tradeDate(latestDate)
                .topGainers(stockDataHelper.toRankVOList(dailyQuoteMapper.selectTopGainers(latestDate, RANK_LIMIT)))
                .topLosers(stockDataHelper.toRankVOList(dailyQuoteMapper.selectTopLosers(latestDate, RANK_LIMIT)))
                .topAmount(stockDataHelper.toRankVOList(dailyQuoteMapper.selectTopAmount(latestDate, RANK_LIMIT)))
                .topTurnover(dailyQuoteMapper.selectTopTurnover(latestDate, RANK_LIMIT))
                .build();
    }

    @Override
    @Cacheable(value = "marketTemperature", key = "#root.target.resolveTemperatureTradeDate(#tradeDate)")
    public MarketTemperatureVO getMarketTemperature(String tradeDate) {
        String date = resolveTemperatureTradeDate(tradeDate);
        if ("empty".equals(date)) {
            return MarketTemperatureVO.builder()
                    .upCount(0).downCount(0).flatCount(0)
                    .limitUpCount(0).limitDownCount(0)
                    .build();
        }
        Map<String, Object> agg = dailyQuoteMapper.selectMarketTemperature(date);
        if (agg == null) {
            return MarketTemperatureVO.builder()
                    .tradeDate(date)
                    .upCount(0).downCount(0).flatCount(0)
                    .limitUpCount(0).limitDownCount(0)
                    .build();
        }
        return MarketTemperatureVO.builder()
                .tradeDate(date)
                .upCount(toInt(agg.get("up_count")))
                .downCount(toInt(agg.get("down_count")))
                .flatCount(toInt(agg.get("flat_count")))
                .limitUpCount(toInt(agg.get("limit_up_count")))
                .limitDownCount(toInt(agg.get("limit_down_count")))
                .build();
    }

    /**
     * 将聚合查询返回的数值安全转为 int（兼容 Number/String/null）。
     */
    private static int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public PageResult<StockListDTO> getStockList(StockListQueryDTO query) {
        String tradeDate = dailyQuoteMapper.selectLatestTradeDate();
        if (tradeDate == null) {
            return PageResult.of(Collections.emptyList(), 0L, 1, DEFAULT_PAGE_SIZE);
        }

        // rankType 解析：null/非法 -> GAINERS
        RankingType rankType = RankingType.fromCode(query.getRankType());
        if (rankType == null) {
            rankType = RankingType.GAINERS;
        }

        // 排序字段与方向：sortBy 优先，非法或缺失时回退到 rankType 默认
        String sortBy = query.getSortBy();
        String order = query.getOrder();
        if (sortBy == null || sortBy.isEmpty() || !SORT_COLUMNS.containsKey(sortBy)) {
            if (sortBy != null && !sortBy.isEmpty()) {
                log.warn("getStockList: 非法 sortBy={}, 回退到 rankType={} 默认排序", sortBy, rankType);
            }
            String[] def = rankDefaultSort(rankType);
            sortBy = def[0];
            order = def[1];
        }
        if (order == null || order.isEmpty()) {
            order = "desc";
        }
        String sortClause = buildSortClause(sortBy, order);

        // market 白名单校验
        String market = query.getMarket();
        if (market != null && !market.isEmpty() && !MARKET_WHITELIST.contains(market)) {
            log.warn("getStockList: 非法 market={}, 忽略该过滤", market);
            market = null;
        }

        // 分页规范化
        int page = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int size = query.getSize() == null || query.getSize() < 1
                ? DEFAULT_PAGE_SIZE : Math.min(query.getSize(), MAX_PAGE_SIZE);
        int offset = (page - 1) * size;

        String industryCode = query.getIndustryCode();
        List<StockListDTO> list = dailyQuoteMapper.selectStockList(
                tradeDate, industryCode, market, sortClause, size, offset);
        long total = dailyQuoteMapper.selectStockListCount(tradeDate, industryCode, market);
        return PageResult.of(list, total, page, size);
    }

    /**
     * 各榜单类型的默认排序（字段 + 方向）。
     */
    private static String[] rankDefaultSort(RankingType rankType) {
        return switch (rankType) {
            case GAINERS -> new String[]{"pctChg", "desc"};
            case LOSERS -> new String[]{"pctChg", "asc"};
            case TURNOVER -> new String[]{"turnoverRate", "desc"};
            case AMOUNT -> new String[]{"amount", "desc"};
            case VOLUME_RATIO -> new String[]{"volumeRatio", "desc"};
            case AMPLITUDE -> new String[]{"amplitude", "desc"};
        };
    }

    /**
     * 拼装 ORDER BY 片段（经白名单校验，可安全以 ${} 注入）。
     * <p>
     * 对 LEFT JOIN daily_basic 派生字段（可能 NULL）追加 {@code (col IS NULL)} 使 NULL 排最后。
     */
    private static String buildSortClause(String sortBy, String direction) {
        String column = SORT_COLUMNS.get(sortBy);
        String dir = "asc".equalsIgnoreCase(direction) ? "ASC" : "DESC";
        if (NULL_LAST_SORT_FIELDS.contains(sortBy)) {
            return "(" + column + " IS NULL), " + column + " " + dir;
        }
        return column + " " + dir;
    }
}
