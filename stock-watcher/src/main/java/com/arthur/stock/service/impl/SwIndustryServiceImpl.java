package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.BoardEnum;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.constant.ListStatusEnum;
import com.arthur.stock.constant.MarketThresholdConstants;
import com.arthur.stock.constant.SwIndustryConstants;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.IndexClassifyDTO;
import com.arthur.stock.dto.tushare.IndexClassifyQueryDTO;
import com.arthur.stock.dto.tushare.IndexMemberDTO;
import com.arthur.stock.dto.tushare.IndexMemberQueryDTO;
import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.mapper.SwIndustryMapper;
import com.arthur.stock.mapper.SwIndustryMemberMapper;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.model.IndexDailyDO;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.model.SwIndustryDO;
import com.arthur.stock.model.SwIndustryMemberDO;
import com.arthur.stock.service.DataCheckable;
import com.arthur.stock.service.IndexDailyService;
import com.arthur.stock.service.SwIndustryService;
import com.arthur.stock.vo.IndustryMemberVO;
import com.arthur.stock.vo.IndustryMoneyflowVO;
import com.arthur.stock.vo.IndustryRankingVO;
import com.arthur.stock.vo.IndustryValuationVO;
import com.arthur.stock.vo.SwIndustryVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 申万行业分类服务实现。
 * <p>
 * 数据源：tushare index_classify（doc_id=181）+ index_member_all（doc_id=335），按 SW2021 版本。
 * 落库策略：
 * <ul>
 *   <li>分类：按 src 先删后插（全量替换）；</li>
 *   <li>成分股：按 (ts_code, index_code, update_date) 业务键先删后插，实现幂等 upsert。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwIndustryServiceImpl implements SwIndustryService, DataCheckable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** index_member_all 单次分页大小（Tushare 上限 2000） */
    private static final int MEMBER_PAGE_SIZE = 2000;

    /** 成分股批量插入批次大小 */
    private static final int INSERT_BATCH = 500;

    private final TushareClient tushareClient;
    private final SwIndustryMapper swIndustryMapper;
    private final SwIndustryMemberMapper swIndustryMemberMapper;
    private final DailyQuoteMapper dailyQuoteMapper;
    private final IndexDailyService indexDailyService;
    private final StockBasicMapper stockBasicMapper;

    @Override
    public int fetchAndSaveClassify(String src) {
        String effectiveSrc = src == null ? SwIndustryConstants.SW_SRC : src;
        log.info("Fetching sw_industry classify: src={}", effectiveSrc);

        IndexClassifyQueryDTO param = IndexClassifyQueryDTO.builder()
                .src(effectiveSrc)
                .build();
        List<IndexClassifyDTO> rows = tushareClient.indexClassify(param);
        if (rows.isEmpty()) {
            log.info("No sw_industry classify data for src={}", effectiveSrc);
            return 0;
        }

        // 按 src 全量替换
        swIndustryMapper.delete(new LambdaQueryWrapper<SwIndustryDO>()
                .eq(SwIndustryDO::getSrc, effectiveSrc));

        List<SwIndustryDO> entities = rows.stream()
                .map(dto -> toClassifyEntity(dto, effectiveSrc))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        int count = 0;
        for (List<SwIndustryDO> batch : Lists.partition(entities, INSERT_BATCH)) {
            count += swIndustryMapper.insertBatch(batch);
        }
        log.info("Saved {} sw_industry classify records for src={}", count, effectiveSrc);
        return count;
    }

    @Override
    public int fetchAndSaveMembers(String indexCode, String src) {
        String effectiveSrc = src == null ? SwIndustryConstants.SW_SRC : src;
        log.info("Fetching sw_industry_member by index: indexCode={}, src={}", indexCode, effectiveSrc);

        IndexMemberQueryDTO param = IndexMemberQueryDTO.builder()
                .indexCode(indexCode)
                .src(effectiveSrc)
                .build();
        List<IndexMemberDTO> rows = tushareClient.indexMemberAll(param);
        return persistMembers(rows, effectiveSrc);
    }

    @Override
    public int fetchAndSaveAllMembers(String src) {
        String effectiveSrc = src == null ? SwIndustryConstants.SW_SRC : src;
        log.info("Fetching sw_industry_member (paginated, size={}): src={}", MEMBER_PAGE_SIZE, effectiveSrc);

        int total = 0;
        int offset = 0;
        String today = LocalDate.now().format(DATE_FMT);
        while (true) {
            IndexMemberQueryDTO param = IndexMemberQueryDTO.builder()
                    .src(effectiveSrc)
                    .build();
            List<IndexMemberDTO> page = tushareClient.indexMemberAll(param, offset, MEMBER_PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            // 接口未返回 update_date，persistMembers 用指定 today 作为同步快照日
            total += persistMembers(page, effectiveSrc, today);
            log.info("sw_industry_member page fetched: offset={}, size={}, total={}", offset, page.size(), total);

            if (page.size() < MEMBER_PAGE_SIZE) {
                break;
            }
            offset += MEMBER_PAGE_SIZE;
        }
        log.info("Saved {} sw_industry_member records for src={}", total, effectiveSrc);
        return total;
    }

    @Override
    public String getLatestL1Industry(String tsCode) {
        SwIndustryMemberDO m = swIndustryMemberMapper.selectLatestL1ByTsCode(tsCode);
        return m == null ? null : m.getIndexCode();
    }

    @Override
    public Map<String, String> getLatestL1Industries(List<String> tsCodes) {
        if (tsCodes == null || tsCodes.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SwIndustryMemberDO> rows = swIndustryMemberMapper.selectLatestL1ByTsCodes(tsCodes);
        Map<String, String> result = new HashMap<>(rows.size());
        for (SwIndustryMemberDO m : rows) {
            result.put(m.getTsCode(), m.getIndexCode());
        }
        return result;
    }

    @Override
    public String getL1IndustryAt(String tsCode, String tradeDate) {
        SwIndustryMemberDO m = swIndustryMemberMapper.selectL1AtDate(tsCode, tradeDate);
        return m == null ? null : m.getIndexCode();
    }

    @Override
    public SwIndustryVO getCurrentL1ByTsCode(String tsCode) {
        SwIndustryMemberDO m = swIndustryMemberMapper.selectLatestL1ByTsCode(tsCode);
        if (m == null) {
            return null;
        }
        return SwIndustryVO.builder()
                .industryCode(m.getIndexCode())
                .industryName(m.getIndexName())
                .build();
    }

    @Override
    public List<SwIndustryVO> listByLevel(int level) {
        return listByLevel(level, SwIndustryConstants.SW_SRC);
    }

    public List<SwIndustryVO> listByLevel(int level, String src) {
        List<SwIndustryDO> rows = swIndustryMapper.selectByLevel(level, src);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream()
                .map(d -> SwIndustryVO.builder()
                        .industryCode(d.getIndexCode())
                        .industryName(d.getIndexName())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @org.springframework.cache.annotation.Cacheable(value = "sectorRanking", key = "#tradeDate != null ? #tradeDate : 'latest'")
    public List<IndustryRankingVO> getIndustryRanking(String tradeDate) {
        // 1. 获取28个申万一级行业
        List<SwIndustryVO> industries = listByLevel(1);
        if (industries.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 确定交易日
        if (tradeDate == null) {
            tradeDate = dailyQuoteMapper.selectLatestTradeDate();
        }

        // 3. 查询全量当前一级成分股
        List<SwIndustryMemberDO> allMembers = swIndustryMemberMapper.selectAllCurrentL1Members(SwIndustryConstants.SW_SRC);
        Map<String, List<SwIndustryMemberDO>> membersByIndustry = groupMembersByIndustry(allMembers);

        // 4. 查询行业指数行情
        Map<String, IndexDailyDO> indexDailyMap = buildIndexDailyMap(industries, tradeDate);
        // 4.1 查询各行业指数近20日行情（DESC，[0]最新），用于计算 pctChg5d/pctChg20d
        Map<String, List<Double>> indexTrendMap = buildIndexTrendMap(industries);

        // 5. 查询成分股行情 + 股票基础信息（含 name/market，用于涨跌停阈值判定）
        Map<String, DailyQuoteDO> quoteMap;
        Map<String, StockBasicDO> stockBasicMap;
        if (allMembers != null && !allMembers.isEmpty()) {
            List<String> allTsCodes = allMembers.stream()
                    .map(SwIndustryMemberDO::getTsCode)
                    .distinct()
                    .collect(Collectors.toList());
            quoteMap = buildStockQuoteMap(allTsCodes, tradeDate);
            stockBasicMap = buildStockBasicMap();
        } else {
            stockBasicMap = new HashMap<>();
            quoteMap = new HashMap<>();
        }

        // 6. 构建返回结果
        String finalTradeDate = tradeDate;
        return industries.stream()
                .map(ind -> buildIndustryRankingVO(ind, membersByIndustry, indexDailyMap, indexTrendMap, quoteMap, stockBasicMap, finalTradeDate))
                .collect(Collectors.toList());
    }

    private Map<String, List<SwIndustryMemberDO>> groupMembersByIndustry(List<SwIndustryMemberDO> allMembers) {
        if (allMembers == null || allMembers.isEmpty()) {
            return Collections.emptyMap();
        }
        return allMembers.stream()
                .collect(Collectors.groupingBy(SwIndustryMemberDO::getIndexCode));
    }

    private Map<String, IndexDailyDO> buildIndexDailyMap(List<SwIndustryVO> industries, String tradeDate) {
        // 库内 sw_industry.index_code 已带 .SI 后缀（如 801010.SI），直接用作 index_daily.ts_code 查询
        List<String> indexCodes = industries.stream()
                .map(SwIndustryVO::getIndustryCode)
                .collect(Collectors.toList());
        List<IndexDailyDO> indexDailyList = indexDailyService.getByCodesAndTradeDate(indexCodes, tradeDate);
        Map<String, IndexDailyDO> map = new HashMap<>();
        for (IndexDailyDO d : indexDailyList) {
            map.put(d.getTsCode(), d);
        }
        return map;
    }

    private Map<String, DailyQuoteDO> buildStockQuoteMap(List<String> tsCodes, String tradeDate) {
        List<DailyQuoteDO> quotes = dailyQuoteMapper.selectByCodesAndTradeDate(tsCodes, tradeDate);
        Map<String, DailyQuoteDO> map = new HashMap<>();
        for (DailyQuoteDO q : quotes) {
            map.put(q.getTsCode(), q);
        }
        return map;
    }

    private Map<String, StockBasicDO> buildStockBasicMap() {
        // 仅查询下游用到的字段（tsCode/name/market），避免全字段加载 5000+ 行
        List<StockBasicDO> stocks = stockBasicMapper.selectList(
                new LambdaQueryWrapper<StockBasicDO>()
                        .select(StockBasicDO::getTsCode, StockBasicDO::getName, StockBasicDO::getMarket));
        Map<String, StockBasicDO> map = new HashMap<>(stocks.size());
        for (StockBasicDO s : stocks) {
            map.put(s.getTsCode(), s);
        }
        return map;
    }

    /**
     * 批量取各行业指数近20日收盘（按 trade_date DESC，[0] 为最新）。
     * 行业指数数据量小（28 个 × 20 条），按代码循环调用可接受，且有 sectorRanking 缓存兜底。
     */
    private Map<String, List<Double>> buildIndexTrendMap(List<SwIndustryVO> industries) {
        Map<String, List<Double>> map = new HashMap<>(industries.size());
        for (SwIndustryVO ind : industries) {
            String indexCode = ind.getIndustryCode();
            List<IndexDailyDO> list = indexDailyService.getByCodeOrderByTradeDate(indexCode, 20);
            if (list == null || list.isEmpty()) {
                map.put(indexCode, Collections.emptyList());
                continue;
            }
            List<Double> closes = list.stream()
                    .map(IndexDailyDO::getClose)
                    .filter(Objects::nonNull)
                    .map(BigDecimal::doubleValue)
                    .collect(Collectors.toList());
            map.put(indexCode, closes);
        }
        return map;
    }

    private IndustryRankingVO buildIndustryRankingVO(
            SwIndustryVO ind,
            Map<String, List<SwIndustryMemberDO>> membersByIndustry,
            Map<String, IndexDailyDO> indexDailyMap,
            Map<String, List<Double>> indexTrendMap,
            Map<String, DailyQuoteDO> quoteMap,
            Map<String, StockBasicDO> stockBasicMap,
            String tradeDate) {

        String idxCode = ind.getIndustryCode();
        // 库内 index_code 已带 .SI 后缀，直接作为 index_daily.ts_code 使用
        String indexTsCode = idxCode;
        IndexDailyDO idxDaily = indexDailyMap.get(indexTsCode);

        List<SwIndustryMemberDO> members = membersByIndustry.getOrDefault(idxCode, Collections.emptyList());
        int constituentCount = members.size();

        List<DailyQuoteDO> memberQuotes = members.stream()
                .map(m -> quoteMap.get(m.getTsCode()))
                .filter(q -> q != null && q.getPctChg() != null)
                .sorted(Comparator.comparing(DailyQuoteDO::getPctChg).reversed())
                .collect(Collectors.toList());
        int activeCount = memberQuotes.size();

        // 涨跌 / 涨跌停 家数统计
        // 注：memberQuotes 已在上方 .filter(q.getPctChg() != null) 剔除停牌/缺行情股，
        // 此处 getPctChg() 必非 null，无需再次判空。
        int upCount = 0;
        int downCount = 0;
        int limitUpCount = 0;
        int limitDownCount = 0;
        for (DailyQuoteDO q : memberQuotes) {
            double pctChg = q.getPctChg().doubleValue();
            if (pctChg > 0) {
                upCount++;
            } else if (pctChg < 0) {
                downCount++;
            }
            double threshold = resolveLimitThreshold(stockBasicMap.get(q.getTsCode()));
            if (pctChg >= threshold) {
                limitUpCount++;
            }
            if (pctChg <= -threshold) {
                limitDownCount++;
            }
        }

        // N 日趋势（指数近20日收盘，DESC，[0] 最新）
        Double pctChg5d = null;
        Double pctChg20d = null;
        List<Double> closes = indexTrendMap.getOrDefault(indexTsCode, Collections.emptyList());
        if (closes.size() >= 5) {
            double base5 = closes.get(4);
            if (base5 != 0.0) {
                pctChg5d = (closes.get(0) / base5 - 1) * 100;
            } else {
                log.warn("指数 {} 近5日基准收盘价为 0，数据异常，跳过 pctChg5d 计算", indexTsCode);
            }
        }
        if (closes.size() >= 20) {
            double base20 = closes.get(19);
            if (base20 != 0.0) {
                pctChg20d = (closes.get(0) / base20 - 1) * 100;
            } else {
                log.warn("指数 {} 近20日基准收盘价为 0，数据异常，跳过 pctChg20d 计算", indexTsCode);
            }
        }

        IndustryRankingVO.IndustryRankingVOBuilder builder = IndustryRankingVO.builder()
                .industryCode(idxCode)
                .industryName(ind.getIndustryName())
                .indexCode(indexTsCode)
                .constituentCount(constituentCount)
                .activeCount(activeCount)
                .tradeDate(tradeDate)
                .upCount(upCount)
                .downCount(downCount)
                .limitUpCount(limitUpCount)
                .limitDownCount(limitDownCount)
                .pctChg5d(pctChg5d)
                .pctChg20d(pctChg20d);

        if (idxDaily != null) {
            builder.pctChg(idxDaily.getPctChg())
                    .amount(idxDaily.getAmount());
        }

        if (!memberQuotes.isEmpty()) {
            DailyQuoteDO topGainer = memberQuotes.get(0);
            DailyQuoteDO topLoser = memberQuotes.get(memberQuotes.size() - 1);
            builder.topGainerCode(topGainer.getTsCode())
                    .topGainerName(getNameOrDefault(stockBasicMap.get(topGainer.getTsCode())))
                    .topGainerPctChg(topGainer.getPctChg())
                    .topLoserCode(topLoser.getTsCode())
                    .topLoserName(getNameOrDefault(stockBasicMap.get(topLoser.getTsCode())))
                    .topLoserPctChg(topLoser.getPctChg());
        }

        return builder.build();
    }

    /**
     * 根据股票板块与 ST 标识，返回对应的涨跌停判定阈值（百分比）。
     * 口径与 DailyQuoteMapper.xml selectMarketTemperature 一致：
     * ST 优先（name 以 "ST" 开头）；其次按 market 板块；其余按主板兜底。
     */
    private double resolveLimitThreshold(StockBasicDO stock) {
        if (stock == null) {
            return MarketThresholdConstants.MAIN_BOARD;
        }
        if (stock.getName() != null && stock.getName().startsWith("ST")) {
            return MarketThresholdConstants.ST;
        }
        BoardEnum market = stock.getMarket();
        String code = market == null ? null : market.getCode();
        if (MarketThresholdConstants.MARKET_BSE.equals(code)) {
            return MarketThresholdConstants.BSE;
        }
        if (MarketThresholdConstants.MARKET_GEM.equals(code)
                || MarketThresholdConstants.MARKET_STAR.equals(code)) {
            return MarketThresholdConstants.GEM_STAR;
        }
        return MarketThresholdConstants.MAIN_BOARD;
    }

    private String getNameOrDefault(StockBasicDO stock) {
        return stock != null && stock.getName() != null ? stock.getName() : "";
    }

    @Override
    public PageResult<IndustryMemberVO> getIndustryMembers(String industryCode, String tradeDate, int page, int size, String keyword) {
        if (tradeDate == null) {
            tradeDate = dailyQuoteMapper.selectLatestTradeDate();
        }
        int offset = (page - 1) * size;
        List<IndustryMemberVO> list = dailyQuoteMapper.selectMembersWithQuote(industryCode, tradeDate, keyword, size, offset);
        long total = dailyQuoteMapper.countMembersWithQuote(industryCode, tradeDate, keyword);
        return PageResult.of(list, total, page, size);
    }

    @Override
    @org.springframework.cache.annotation.Cacheable(value = "sectorMoneyflow", key = "#tradeDate != null ? #tradeDate : 'latest'")
    public List<IndustryMoneyflowVO> getIndustryMoneyflow(String tradeDate) {
        List<SwIndustryVO> industries = listByLevel(1);
        if (industries.isEmpty()) {
            return Collections.emptyList();
        }
        if (tradeDate == null) {
            tradeDate = dailyQuoteMapper.selectLatestTradeDate();
        }
        List<Map<String, Object>> rows = swIndustryMemberMapper.selectMoneyflowGroupByIndustry(tradeDate);
        Map<String, Map<String, Object>> aggByCode = new HashMap<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                Object code = r.get("index_code");
                if (code != null) {
                    aggByCode.put(code.toString(), r);
                }
            }
        }
        String finalTradeDate = tradeDate;
        return industries.stream()
                .map(ind -> buildMoneyflowVO(ind, aggByCode.get(ind.getIndustryCode()), finalTradeDate))
                .collect(Collectors.toList());
    }

    private IndustryMoneyflowVO buildMoneyflowVO(SwIndustryVO ind, Map<String, Object> row, String tradeDate) {
        IndustryMoneyflowVO.IndustryMoneyflowVOBuilder b = IndustryMoneyflowVO.builder()
                .industryCode(ind.getIndustryCode())
                .industryName(ind.getIndustryName())
                .tradeDate(tradeDate);
        if (row != null) {
            b.mainNetInflow(toDouble(row.get("main_net")))
                    .elgNetInflow(toDouble(row.get("elg_net")))
                    .lgNetInflow(toDouble(row.get("lg_net")))
                    .mdNetInflow(toDouble(row.get("md_net")))
                    .smNetInflow(toDouble(row.get("sm_net")))
                    .netMfAmount(toDouble(row.get("net_mf")));
        }
        return b.build();
    }

    @Override
    @org.springframework.cache.annotation.Cacheable(value = "sectorValuation", key = "#tradeDate != null ? #tradeDate : 'latest'")
    public List<IndustryValuationVO> getIndustryValuation(String tradeDate) {
        List<SwIndustryVO> industries = listByLevel(1);
        if (industries.isEmpty()) {
            return Collections.emptyList();
        }
        if (tradeDate == null) {
            tradeDate = dailyQuoteMapper.selectLatestTradeDate();
        }
        List<Map<String, Object>> rows = swIndustryMemberMapper.selectValuationGroupByIndustry(tradeDate);
        Map<String, Map<String, Object>> aggByCode = new HashMap<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                Object code = r.get("index_code");
                if (code != null) {
                    aggByCode.put(code.toString(), r);
                }
            }
        }
        String finalTradeDate = tradeDate;
        return industries.stream()
                .map(ind -> buildValuationVO(ind, aggByCode.get(ind.getIndustryCode()), finalTradeDate))
                .collect(Collectors.toList());
    }

    private IndustryValuationVO buildValuationVO(SwIndustryVO ind, Map<String, Object> row, String tradeDate) {
        IndustryValuationVO.IndustryValuationVOBuilder b = IndustryValuationVO.builder()
                .industryCode(ind.getIndustryCode())
                .industryName(ind.getIndustryName())
                .tradeDate(tradeDate);
        if (row != null) {
            b.peTtm(toDouble(row.get("pe_ttm")))
                    .pb(toDouble(row.get("pb")));
        }
        return b.build();
    }

    /**
     * 将聚合查询返回的数值安全转为 Double（兼容 Number/String/null，NaN/Inf 返回 null）。
     */
    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        try {
            double d = Double.parseDouble(value.toString());
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 内部方法 ====================
    private SwIndustryDO toClassifyEntity(IndexClassifyDTO dto, String src) {
        if (dto == null || dto.getIndexCode() == null) {
            return null;
        }
        return SwIndustryDO.builder()
                .indexCode(dto.getIndexCode())
                .indexName(dto.getIndexName())
                .level(dto.getLevel())
                .parentCode(dto.getParentCode())
                .src(src)
                .build();
    }

    private SwIndustryMemberDO toMemberEntity(IndexMemberDTO dto, String src, String updateDate) {
        if (dto == null || dto.getTsCode() == null || dto.getIndexCode() == null) {
            return null;
        }
        return SwIndustryMemberDO.builder()
                .tsCode(dto.getTsCode())
                .indexCode(dto.getIndexCode())
                .indexName(dto.getIndexName())
                .inDate(dto.getInDate())
                .outDate(dto.getOutDate())
                .isNew("Y".equalsIgnoreCase(dto.getIsNew()))
                .src(src)
                .updateDate(updateDate)
                .build();
    }

    /**
     * 成分股落库：update_date 取 in_date（无则 out_date，再无则当天）。
     */
    private int persistMembers(List<IndexMemberDTO> rows, String src) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<SwIndustryMemberDO> entities = rows.stream()
                .map(dto -> {
                    String updateDate = firstNonBlank(dto.getInDate(), dto.getOutDate(),
                            LocalDate.now().format(DATE_FMT));
                    return toMemberEntity(dto, src, updateDate);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        int count = 0;
        for (List<SwIndustryMemberDO> batch : Lists.partition(entities, INSERT_BATCH)) {
            swIndustryMemberMapper.deleteBatchByKeys(batch);
            count += swIndustryMemberMapper.insertBatch(batch);
        }
        return count;
    }

    /**
     * 成分股落库（指定 updateDate），按 (ts_code, index_code, update_date) 批量先删后插。
     */
    private int persistMembers(List<IndexMemberDTO> rows, String src, String updateDate) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<SwIndustryMemberDO> entities = rows.stream()
                .map(dto -> toMemberEntity(dto, src, updateDate))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        int count = 0;
        for (List<SwIndustryMemberDO> batch : Lists.partition(entities, INSERT_BATCH)) {
            swIndustryMemberMapper.deleteBatchByKeys(batch);
            count += swIndustryMemberMapper.insertBatch(batch);
        }
        return count;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return LocalDate.now().format(DATE_FMT);
    }

    @Override
    public Map<String, Map<String, String>> getL1IndustriesPit(List<String> tsCodes, String startDate, String endDate) {
        if (tsCodes == null || tsCodes.isEmpty()) {
            return Collections.emptyMap();
        }
        // 1. 一次性查全部历史一级成分股记录（按 ts_code, update_date 升序）
        List<SwIndustryMemberDO> all = swIndustryMemberMapper.selectAllL1HistoryByTsCodes(tsCodes);
        // 2. 按 ts_code 分组（已按 update_date 升序）
        Map<String, List<SwIndustryMemberDO>> byCode = all.stream()
                .collect(Collectors.groupingBy(SwIndustryMemberDO::getTsCode));
        Map<String, Map<String, String>> result = new HashMap<>();
        if (startDate == null || endDate == null) {
            // 无区间约束：退化为按 update_date 索引的 map（调用方应总是传区间）
            for (Map.Entry<String, List<SwIndustryMemberDO>> e : byCode.entrySet()) {
                Map<String, String> inner = new HashMap<>();
                for (SwIndustryMemberDO m : e.getValue()) {
                    inner.put(m.getUpdateDate(), m.getIndexCode());
                }
                if (!inner.isEmpty()) {
                    result.put(e.getKey(), inner);
                }
            }
            return result;
        }
        // 3. 有区间：穷举 [startDate, endDate] 的自然日，对每个 ts_code forward-fill
        List<String> dates = enumerateDates(startDate, endDate);
        for (Map.Entry<String, List<SwIndustryMemberDO>> e : byCode.entrySet()) {
            List<SwIndustryMemberDO> records = e.getValue();  // update_date 升序
            Map<String, String> inner = new HashMap<>();
            int idx = -1;  // 当前生效记录的下标
            for (String d : dates) {
                // 推进 idx 到最后一个 update_date <= d 的记录
                while (idx + 1 < records.size()) {
                    String ud = records.get(idx + 1).getUpdateDate();
                    if (ud != null && ud.compareTo(d) <= 0) {
                        idx++;
                    } else {
                        break;
                    }
                }
                if (idx >= 0) {
                    inner.put(d, records.get(idx).getIndexCode());
                }
                // idx < 0：该日早于任何 update_date → 不下发（engine 静默跳过）
            }
            if (!inner.isEmpty()) {
                result.put(e.getKey(), inner);
            }
        }
        return result;
    }

    /** 枚举 [startDate, endDate] 区间内的自然日（yyyyMMdd），含两端；解析失败返回空。 */
    private static List<String> enumerateDates(String startDate, String endDate) {
        List<String> out = new ArrayList<>();
        try {
            LocalDate s = LocalDate.parse(startDate, DATE_FMT);
            LocalDate e = LocalDate.parse(endDate, DATE_FMT);
            for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) {
                out.add(d.format(DATE_FMT));
            }
        } catch (Exception ignore) {
            // 日期解析失败 → 返回空（不 forward-fill）
        }
        return out;
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.SW_INDUSTRY.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = swIndustryMapper.selectCount(null) + swIndustryMemberMapper.selectCount(null);

            // Check 1: level1 行业数量（ERROR）
            int level1Count = swIndustryMapper.countLevel1();
            boolean level1Passed = level1Count >= 28 && level1Count <= 35;
            items.add(DataCheckItem.builder()
                    .name("level1_count")
                    .displayName("一级行业数量检测")
                    .passed(level1Passed)
                    .level(CheckLevel.ERROR)
                    .message(level1Passed ? "通过，一级行业 " + level1Count + " 个"
                            : "异常，一级行业 " + level1Count + " 个（期望 28-35）")
                    .build());

            // Check 2: 股票覆盖率（WARN）
            boolean coveragePassed;
            String coverageMsg;
            if (totalRows == 0) {
                coveragePassed = true;
                coverageMsg = "表为空，跳过检测";
            } else {
                int coveredStocks = swIndustryMemberMapper.countCoveredStocks();
                long listedStocks = stockBasicMapper.selectCount(
                        new LambdaQueryWrapper<StockBasicDO>()
                                .eq(StockBasicDO::getListStatus, ListStatusEnum.LISTED)
                );
                if (listedStocks == 0) {
                    coveragePassed = true;
                    coverageMsg = "在市股票数为 0，跳过检测";
                } else {
                    double ratio = (double) coveredStocks / listedStocks;
                    coveragePassed = ratio >= 0.9;
                    coverageMsg = String.format("覆盖率 %.1f%%（%d/%d）%s",
                            ratio * 100, coveredStocks, listedStocks,
                            coveragePassed ? "，通过" : "，低于 90%");
                }
            }
            items.add(DataCheckItem.builder()
                    .name("stock_coverage")
                    .displayName("股票覆盖率检测")
                    .passed(coveragePassed)
                    .level(CheckLevel.WARN)
                    .message(coverageMsg)
                    .build());

            // Check 3: code_name 一致性（WARN）
            int mismatchCount = swIndustryMapper.countCodeNameMismatch();
            boolean mismatchPassed = mismatchCount == 0;
            items.add(DataCheckItem.builder()
                    .name("code_name_match")
                    .displayName("行业代码名称一致性检测")
                    .passed(mismatchPassed)
                    .level(CheckLevel.WARN)
                    .message(mismatchPassed ? "通过，无不一致"
                            : "存在 " + mismatchCount + " 个 index_code 对应多个名称")
                    .build());

            // Check 4: 日期逻辑（ERROR）
            int dateLogicErrors = swIndustryMemberMapper.countDateLogicErrors();
            boolean dateLogicPassed = dateLogicErrors == 0;
            items.add(DataCheckItem.builder()
                    .name("date_logic")
                    .displayName("成分股日期逻辑检测")
                    .passed(dateLogicPassed)
                    .level(CheckLevel.ERROR)
                    .message(dateLogicPassed ? "通过，无日期逻辑错误"
                            : "存在 " + dateLogicErrors + " 条 in_date > out_date 的记录")
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.SW_INDUSTRY.getLabel())
                    .totalRows(totalRows)
                    .latestDate(null)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for sw_industry", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.SW_INDUSTRY.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
