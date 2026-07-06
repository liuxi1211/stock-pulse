package com.arthur.stock.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.cache.ScreenerResultCache;
import com.arthur.stock.client.ScreenerClient;
import com.arthur.stock.constant.FactorKey;
import com.arthur.stock.constant.ListStatusEnum;
import com.arthur.stock.constant.ScreenConfigFields;
import com.arthur.stock.constant.ScreenLockStatusEnum;
import com.arthur.stock.constant.ScreenerConstants;
import com.arthur.stock.constant.UniverseEnum;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.screener.CandidateMetaDTO;
import com.arthur.stock.dto.screener.CandidateStockDTO;
import com.arthur.stock.dto.screener.FiltersDTO;
import com.arthur.stock.dto.screener.LockedStockDTO;
import com.arthur.stock.dto.screener.OhlcvBarDTO;
import com.arthur.stock.dto.screener.ScreenPlanCreateRequestDTO;
import com.arthur.stock.dto.screener.ScreenPlanRunRequestDTO;
import com.arthur.stock.dto.screener.ScreenPlanUpdateRequestDTO;
import com.arthur.stock.dto.screener.SnapshotRequestDTO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.mapper.DailyBasicMapper;
import com.arthur.stock.mapper.FinaIndicatorMapper;
import com.arthur.stock.mapper.ScreenLockMapper;
import com.arthur.stock.mapper.ScreenPlanMapper;
import com.arthur.stock.mapper.ScreenResultMapper;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.model.DailyBasicDO;
import com.arthur.stock.model.FinaIndicatorDO;
import com.arthur.stock.model.ScreenLockDO;
import com.arthur.stock.model.ScreenPlanDO;
import com.arthur.stock.model.ScreenResultDO;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.service.DailyQuoteService;
import com.arthur.stock.service.ScreenerService;
import com.arthur.stock.service.TradeCalendarService;
import com.arthur.stock.util.ScreenConfigValidator;
import com.arthur.stock.vo.LockedStockVO;
import com.arthur.stock.vo.ScreenLockDetailVO;
import com.arthur.stock.vo.ScreenLockVO;
import com.arthur.stock.vo.ScreenPlanVO;
import com.arthur.stock.vo.ScreenResultVO;
import com.arthur.stock.vo.SnapshotResultVO;
import com.arthur.stock.vo.StockContributionVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 多因子选股中心编排实现（spec 003 阶段 2 Task 9/10，FR-10）。
 * <p>
 * 职责：
 * <ol>
 *   <li>方案 CRUD（screen_plan）；</li>
 *   <li>执行编排 {@link #runPlan}：解析 plan.screenConfig → 候选池解析 → candidates 拼装
 *       （OHLCV 历史 + meta + TODO 基本面）→ HTTP 调 engine snapshot → 落库 screen_result。</li>
 * </ol>
 * <p>
 * 简化项（TODO）：
 * <ul>
 *   <li>基本面（daily_basic）表未建立，candidates.fundamentals 暂为空 Map；</li>
 *   <li>前复权：本版用 daily_quote 原始价，完整实现应走 KlineService 前复权；</li>
 *   <li>ST/涨跌停/停牌：stock_basic 无对应字段，meta 中全部置 false；</li>
 *   <li>成分股指池（csi300/csi500）未配置成分股表，降级为全市场并 warn；</li>
 *   <li>候选数上限 {@link #SCREEN_MAX_CANDIDATES}=500，避免单次 HTTP 超时。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenerServiceImpl implements ScreenerService {

    private final ScreenPlanMapper screenPlanMapper;
    private final ScreenResultMapper screenResultMapper;
    private final ScreenLockMapper screenLockMapper;
    private final StockBasicMapper stockBasicMapper;
    private final DailyQuoteMapper dailyQuoteMapper;
    private final DailyBasicMapper dailyBasicMapper;
    private final FinaIndicatorMapper finaIndicatorMapper;
    private final ScreenerClient screenerClient;
    private final DailyQuoteService dailyQuoteService;
    private final TradeCalendarService tradeCalendarService;
    private final ScreenerResultCache resultCache;

    /** 选股 candidates 并行拼装线程池（Bean 名 = screenerExecutor）。 */
    @Qualifier("screenerExecutor")
    private final Executor screenerExecutor;

    // ==================== 方案 CRUD ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScreenPlanVO createPlan(ScreenPlanCreateRequestDTO req) {
        ScreenConfigValidator.validate(req.getScreenConfig());
        ScreenPlanDO plan = new ScreenPlanDO();
        plan.setName(req.getName());
        plan.setDescription(req.getDescription());
        plan.setScreenConfig(req.getScreenConfig() == null ? null : JSON.toJSONString(req.getScreenConfig()));
        screenPlanMapper.insert(plan);
        return toPlanVO(screenPlanMapper.selectById(plan.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScreenPlanVO updatePlan(Long id, ScreenPlanUpdateRequestDTO req) {
        ScreenPlanDO plan = requirePlan(id);
        if (req.getName() != null) {
            plan.setName(req.getName());
        }
        if (req.getDescription() != null) {
            plan.setDescription(req.getDescription());
        }
        if (req.getScreenConfig() != null) {
            ScreenConfigValidator.validate(req.getScreenConfig());
            plan.setScreenConfig(JSON.toJSONString(req.getScreenConfig()));
        }
        screenPlanMapper.updateById(plan);
        return toPlanVO(screenPlanMapper.selectById(id));
    }

    @Override
    public ScreenPlanVO getPlan(Long id) {
        return toPlanVO(requirePlan(id));
    }

    @Override
    public PageResult<ScreenPlanVO> listPlans(int page, int size) {
        Page<ScreenPlanDO> p = screenPlanMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<ScreenPlanDO>().orderByDesc(ScreenPlanDO::getId));
        List<ScreenPlanVO> list = p.getRecords().stream().map(this::toPlanVO).toList();
        return PageResult.of(list, p.getTotal(), page, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePlan(Long id) {
        requirePlan(id);
        screenPlanMapper.deleteById(id);
    }

    // ==================== 选股执行编排（核心） ====================

    @Override
    public ScreenResultVO runPlan(Long id, ScreenPlanRunRequestDTO req) {
        // 1. 查 plan + 解析配置
        ScreenPlanDO plan = requirePlan(id);
        JSONObject config = parseConfig(plan.getScreenConfig());

        // 2. 强类型解析 + req/overrides 合并（消除旧版裸 JSONObject 多重 firstNonNull/pick）
        ScreenResolution r = resolve(config, req);

        // 3. 幂等缓存命中则直接返回（同方案同参数 24h 内复用，避免重复打 engine）
        JSONObject paramsSnapshot = r.toParamsJson();
        ScreenResultVO cached = resultCache.get(id, paramsSnapshot);
        if (cached != null) {
            log.info("选股执行 plan={} 命中幂等缓存，跳过 engine 调用", id);
            return cached;
        }

        // 4. 候选池解析
        List<StockBasicDO> universeStocks = resolveUniverse(r.universe(), config);

        // 5. candidates 并行批量拼装（一次批量 SQL + CompletableFuture 并行映射）
        Map<String, CandidateStockDTO> candidates = buildCandidates(universeStocks, toCompactDate(r.isoDate()));

        // 6. 组装 snapshot 请求并调 engine（HTTP 在事务外）
        SnapshotRequestDTO snapshotReq = new SnapshotRequestDTO();
        snapshotReq.setUniverse(r.universe());
        snapshotReq.setDate(r.isoDate());
        snapshotReq.setCandidates(candidates);
        snapshotReq.setConditions(r.conditions());
        snapshotReq.setRanking(r.ranking());
        snapshotReq.setFilters(r.filters());
        snapshotReq.setTopN(r.topN());
        snapshotReq.setVerboseExcluded(Boolean.FALSE);

        log.info("选股执行 plan={} universe={} date={} candidates={}",
                id, r.universe(), r.isoDate(), candidates.size());
        SnapshotResultVO snapshot = screenerClient.runSnapshot(snapshotReq);

        // 7. 落库（事务只包 DB 写）
        Long resultId = persistResult(id, toCompactDate(r.isoDate()), snapshot, paramsSnapshot);

        // 8. 返回 VO + 写幂等缓存
        ScreenResultVO vo = toResultVO(screenResultMapper.selectById(resultId));
        resultCache.put(id, paramsSnapshot, vo);
        return vo;
    }

    @Override
    public ScreenResultVO getResult(Long resultId) {
        ScreenResultDO result = screenResultMapper.selectById(resultId);
        if (result == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "选股结果不存在");
        }
        return toResultVO(result);
    }

    @Override
    public List<ScreenResultVO> listResultsByPlan(Long planId) {
        List<ScreenResultDO> list = screenResultMapper.selectList(
                new LambdaQueryWrapper<ScreenResultDO>()
                        .eq(ScreenResultDO::getPlanId, planId)
                        .orderByDesc(ScreenResultDO::getId));
        return list.stream().map(this::toResultVO).toList();
    }

    // ==================== 结果锁定与收益追踪（spec 003 阶段 2 Task 11，FR-9） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScreenLockVO lockResult(Long resultId) {
        // 1. 查 result
        ScreenResultDO result = screenResultMapper.selectById(resultId);
        if (result == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "选股结果不存在");
        }

        // 2. 防重复锁定
        Long existCnt = screenLockMapper.selectCount(
                new LambdaQueryWrapper<ScreenLockDO>().eq(ScreenLockDO::getResultId, resultId));
        if (existCnt != null && existCnt > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该选股结果已锁定");
        }

        // 3. 创建 ScreenLockDO（lockDate / stocksJson 冗余自 result；ret 全 null；status=TRACKING）
        ScreenLockDO lock = new ScreenLockDO();
        lock.setResultId(result.getId());
        lock.setPlanId(result.getPlanId());
        lock.setLockDate(result.getScreenDate());
        lock.setStocksJson(result.getStocksJson());
        lock.setStatus(ScreenLockStatusEnum.TRACKING.getCode());
        screenLockMapper.insert(lock);

        return toLockVO(screenLockMapper.selectById(lock.getId()));
    }

    @Override
    public ScreenLockDetailVO getLockDetail(Long lockId) {
        ScreenLockDO lock = screenLockMapper.selectById(lockId);
        if (lock == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "选股锁定记录不存在");
        }

        ScreenLockDetailVO vo = new ScreenLockDetailVO();
        copyLockFields(lock, vo);

        // 解析 stocksJson -> List<LockedStockVO>
        List<LockedStockDTO> dtos = parseStocksJson(lock.getStocksJson());
        List<LockedStockVO> stocks = new ArrayList<>(dtos.size());
        for (LockedStockDTO d : dtos) {
            LockedStockVO s = new LockedStockVO();
            s.setSymbol(d.getSymbol());
            s.setRank(d.getRank());
            s.setScore(d.getScore());
            stocks.add(s);
        }
        vo.setStocks(stocks);

        // 个股贡献明细：以"当前最新交易日"为终点
        vo.setContributions(buildContributions(dtos, lock.getLockDate()));
        return vo;
    }

    @Override
    public List<ScreenLockVO> listLocks(Long planId) {
        LambdaQueryWrapper<ScreenLockDO> qw = new LambdaQueryWrapper<ScreenLockDO>()
                .orderByDesc(ScreenLockDO::getId);
        if (planId != null) {
            qw.eq(ScreenLockDO::getPlanId, planId);
        }
        return screenLockMapper.selectList(qw).stream().map(this::toLockVO).toList();
    }

    @Override
    public List<ScreenLockDO> listTrackingLocks() {
        return screenLockMapper.selectList(
                new LambdaQueryWrapper<ScreenLockDO>()
                        .eq(ScreenLockDO::getStatus, ScreenLockStatusEnum.TRACKING.getCode())
                        .orderByAsc(ScreenLockDO::getId));
    }

    /**
     * 计算并更新某条锁定的追踪收益（5/10/20 交易日等权组合收益 + 沪深300基准）。
     * <p>
     * 优化：①交易日历走 {@link TradeCalendarService}（Caffeine 缓存，替换全表扫描）；
     * ②锁定日 + 各周期日 + 基准的收盘价一次性批量查询，构造 Map 内存查询，消除循环内 selectOne。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyTracking(ScreenLockDO lock) {
        if (lock == null || lock.getLockDate() == null) {
            return;
        }

        // 1. 交易日历（升序，缓存）
        List<String> tradeDates = tradeCalendarService.getSortedTradeDates();
        if (tradeDates.isEmpty()) {
            log.warn("daily_quote 无任何交易日数据，跳过 lock={}", lock.getId());
            return;
        }

        // 2. 解析组合
        List<LockedStockDTO> stocks = parseStocksJson(lock.getStocksJson());
        if (stocks.isEmpty()) {
            log.warn("lock={} 的 stocksJson 为空，跳过", lock.getId());
            return;
        }

        String lockDate = lock.getLockDate();

        // 3. 收集所有需查询的 (ts_code, trade_date) 组合，批量取收盘价
        List<String> symbols = stocks.stream().map(LockedStockDTO::getSymbol).collect(Collectors.toList());
        // 基准也塞进同一批查询
        List<String> codesWithBench = new ArrayList<>(symbols);
        codesWithBench.add(ScreenerConstants.BENCHMARK_CODE);

        // 锁定日收盘价（一次批量）
        Map<String, BigDecimal> lockCloseByCode = batchGetClose(codesWithBench, lockDate);
        Map<String, BigDecimal> lockCloses = filterMap(lockCloseByCode, symbols);
        if (lockCloses.isEmpty()) {
            log.warn("lock={} 在 lockDate={} 所有股票均无收盘价（停牌/缺数据），跳过", lock.getId(), lockDate);
            return;
        }
        BigDecimal benchLockClose = lockCloseByCode.get(ScreenerConstants.BENCHMARK_CODE);

        // 4. 各周期组合收益 + 基准收益（每个周期日一次批量查询）
        for (int n : ScreenerConstants.TRACKING_PERIODS) {
            String nthDate = tradeCalendarService.findNthTradeDateAfter(lockDate, n);
            BigDecimal portfolioRet = computeEqualWeightReturn(lockCloses, symbols, nthDate);
            BigDecimal benchRet = computeBenchmarkReturn(benchLockClose, nthDate);

            switch (n) {
                case 5 -> {
                    lock.setRet5d(portfolioRet);
                    lock.setBenchmarkRet5d(benchRet);
                }
                case 10 -> {
                    lock.setRet10d(portfolioRet);
                    lock.setBenchmarkRet10d(benchRet);
                }
                case 20 -> {
                    lock.setRet20d(portfolioRet);
                    lock.setBenchmarkRet20d(benchRet);
                }
                default -> { /* no-op */ }
            }
        }

        // 5. 20 个交易日全部已填齐（ret20d 已有值）→ DONE
        if (lock.getRet20d() != null) {
            lock.setStatus(ScreenLockStatusEnum.DONE.getCode());
        }

        screenLockMapper.updateById(lock);
    }

    // ==================== 内部：追踪辅助（FR-9） ====================

    /**
     * 解析 stocksJson -> List<LockedStockDTO>。
     */
    private List<LockedStockDTO> parseStocksJson(String stocksJson) {
        if (stocksJson == null || stocksJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<LockedStockDTO> list = JSON.parseArray(stocksJson, LockedStockDTO.class);
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            log.warn("stocksJson 解析失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * daily_quote 全市场 distinct trade_date，升序（已迁移至 {@link TradeCalendarService}，此处保留入口便于追踪链路读取）。
     */
    private List<String> listSortedTradeDates() {
        return tradeCalendarService.getSortedTradeDates();
    }

    /**
     * 一次性批量取多只股票在某交易日��收盘价，返回 ts_code -&gt; close。
     * 替代旧 {@code getClose} 在循环内逐条 selectOne 的 N+1 写法。
     */
    private Map<String, BigDecimal> batchGetClose(List<String> codes, String tradeDate) {
        if (codes == null || codes.isEmpty() || tradeDate == null) {
            return Collections.emptyMap();
        }
        List<DailyQuoteDO> rows = dailyQuoteMapper.selectByCodesAndTradeDate(codes, tradeDate);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, BigDecimal> map = new HashMap<>(rows.size());
        for (DailyQuoteDO q : rows) {
            map.put(q.getTsCode(), q.getClose());
        }
        return map;
    }

    /** 从源 Map 中只保留 keys 指定的键（LinkedHashMap 保序）。 */
    private Map<String, BigDecimal> filterMap(Map<String, BigDecimal> src, List<String> keys) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String k : keys) {
            BigDecimal v = src.get(k);
            if (v != null) {
                out.put(k, v);
            }
        }
        return out;
    }

    /**
     * 等权组合收益：mean over stocks of ((close_N - close_lock)/close_lock)。
     * <p>
     * 优化版：close_N 通过一次批量查询获取，避免循环内单条 select。
     */
    private BigDecimal computeEqualWeightReturn(Map<String, BigDecimal> lockCloses,
                                                List<String> symbols, String nthDate) {
        if (nthDate == null) {
            return null;
        }
        Map<String, BigDecimal> nthCloses = batchGetClose(symbols, nthDate);
        BigDecimal sum = BigDecimal.ZERO;
        int cnt = 0;
        for (Map.Entry<String, BigDecimal> e : lockCloses.entrySet()) {
            BigDecimal closeLock = e.getValue();
            BigDecimal closeN = nthCloses.get(e.getKey());
            if (closeLock == null || closeN == null || closeLock.signum() == 0) {
                continue;
            }
            sum = sum.add(closeN.subtract(closeLock).divide(closeLock, 10, RoundingMode.HALF_UP));
            cnt++;
        }
        if (cnt == 0) {
            return null;
        }
        return sum.divide(BigDecimal.valueOf(cnt), 6, RoundingMode.HALF_UP);
    }

    /**
     * 沪深300基准同期收益。简化：查 daily_quote 的 BENCHMARK_CODE。
     * 库无指数日线 -&gt; 返回 null + warn（不阻断）。
     */
    private BigDecimal computeBenchmarkReturn(BigDecimal benchLockClose, String nthDate) {
        if (nthDate == null || benchLockClose == null || benchLockClose.signum() == 0) {
            return null;
        }
        Map<String, BigDecimal> nth = batchGetClose(List.of(ScreenerConstants.BENCHMARK_CODE), nthDate);
        BigDecimal closeN = nth.get(ScreenerConstants.BENCHMARK_CODE);
        if (closeN == null) {
            log.warn("沪深300({})在 {} 无日线数据，基准收益降级为 null（TODO 指数行情应由独立指数表提供）",
                    ScreenerConstants.BENCHMARK_CODE, nthDate);
            return null;
        }
        return closeN.subtract(benchLockClose).divide(benchLockClose, 6, RoundingMode.HALF_UP);
    }

    /**
     * 构建个股贡献明细：以"当前最新交易日"为终点。
     * <p>
     * 优化版：锁定日 + 最新日收盘价各一次批量查询，消除循环内单条 select。
     * contributionPct = returnPct / 有效股票数（等权）。
     */
    private List<StockContributionVO> buildContributions(List<LockedStockDTO> stocks, String lockDate) {
        List<String> symbols = stocks.stream().map(LockedStockDTO::getSymbol).collect(Collectors.toList());
        String latest = tradeCalendarService.getLatestTradeDate();

        // 批量取锁定日收盘价
        Map<String, BigDecimal> lockCloseMap = batchGetClose(symbols, lockDate);
        // 批量取最新交易日收盘价
        Map<String, BigDecimal> currentCloseMap = latest == null
                ? Collections.emptyMap() : batchGetClose(symbols, latest);

        int validN = 0;
        for (String sym : symbols) {
            if (lockCloseMap.get(sym) != null) {
                validN++;
            }
        }
        BigDecimal divisor = validN > 0 ? BigDecimal.valueOf(validN) : BigDecimal.ONE;

        List<StockContributionVO> out = new ArrayList<>(stocks.size());
        for (LockedStockDTO s : stocks) {
            StockContributionVO c = new StockContributionVO();
            c.setSymbol(s.getSymbol());
            BigDecimal lockClose = lockCloseMap.get(s.getSymbol());
            c.setLockClose(lockClose);
            BigDecimal currentClose = currentCloseMap.get(s.getSymbol());
            c.setCurrentClose(currentClose);

            if (lockClose != null && currentClose != null && lockClose.signum() != 0) {
                BigDecimal ret = currentClose.subtract(lockClose)
                        .divide(lockClose, ScreenerConstants.RETURN_SCALE, RoundingMode.HALF_UP);
                c.setReturnPct(ret);
                c.setContributionPct(ret.divide(divisor, ScreenerConstants.RETURN_SCALE, RoundingMode.HALF_UP));
            }
            out.add(c);
        }
        return out;
    }

    // ==================== 内部：lock VO 转换 ====================

    private ScreenLockVO toLockVO(ScreenLockDO lock) {
        if (lock == null) {
            return null;
        }
        ScreenLockVO vo = new ScreenLockVO();
        copyLockFields(lock, vo);
        return vo;
    }

    private void copyLockFields(ScreenLockDO lock, ScreenLockVO vo) {
        vo.setId(lock.getId());
        vo.setResultId(lock.getResultId());
        vo.setPlanId(lock.getPlanId());
        vo.setLockDate(lock.getLockDate());
        vo.setStatus(lock.getStatus());
        vo.setRet5d(lock.getRet5d());
        vo.setRet10d(lock.getRet10d());
        vo.setRet20d(lock.getRet20d());
        vo.setBenchmarkRet5d(lock.getBenchmarkRet5d());
        vo.setBenchmarkRet10d(lock.getBenchmarkRet10d());
        vo.setBenchmarkRet20d(lock.getBenchmarkRet20d());
        vo.setCreatedAt(lock.getCreatedAt());
        vo.setUpdatedAt(lock.getUpdatedAt());
    }

    // ==================== 内部：候选池解析 ====================

    /**
     * 解析候选池（spec FR-10）。
     * <p>
     * 简化：
     * <ul>
     *   <li>all_a_shares：stock_basic 中 list_status='L' 全部；</li>
     *   <li>csi300/csi500：成分股表未建立，<b>降级为全市场并 warn</b>；</li>
     *   <li>manual：从 screenConfig.stocks 取 tsCode 列表。</li>
     * </ul>
     * 候选数超过 {@link ScreenerConstants#SCREEN_MAX_CANDIDATES} 时截断并 warn。
     */
    private List<StockBasicDO> resolveUniverse(String universe, JSONObject config) {
        List<StockBasicDO> stocks;
        int effectiveMax = ScreenerConstants.SCREEN_MAX_CANDIDATES;
        if (UniverseEnum.MANUAL.getCode().equalsIgnoreCase(universe)) {
            List<String> codes = config.getJSONArray(ScreenConfigFields.STOCKS) == null
                    ? Collections.emptyList()
                    : config.getJSONArray(ScreenConfigFields.STOCKS).toJavaList(String.class);
            if (codes.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "manual 候选池未配置 stocks 列表");
            }
            // manual 用更紧的上限
            if (codes.size() > ScreenerConstants.SCREEN_MANUAL_MAX_CANDIDATES) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "manual 候选股数量超过上限 "
                                + ScreenerConstants.SCREEN_MANUAL_MAX_CANDIDATES
                                + "（当前 " + codes.size() + "）");
            }
            effectiveMax = ScreenerConstants.SCREEN_MANUAL_MAX_CANDIDATES;
            stocks = stockBasicMapper.selectList(new LambdaQueryWrapper<StockBasicDO>()
                    .in(StockBasicDO::getTsCode, codes));
        } else {
            if (!UniverseEnum.ALL_A_SHARES.getCode().equalsIgnoreCase(universe)) {
                log.warn("成分股指池[{}]未配置成分股表，降级为全市场(all_a_shares)", universe);
            }
            stocks = stockBasicMapper.selectList(new LambdaQueryWrapper<StockBasicDO>()
                    .eq(StockBasicDO::getListStatus, ListStatusEnum.LISTED)
                    .orderByAsc(StockBasicDO::getTsCode));
        }

        if (stocks.size() > effectiveMax) {
            log.warn("候选股数 {} 超过上限 {}，已截断；后续支持分批/异步",
                    stocks.size(), effectiveMax);
            stocks = stocks.subList(0, effectiveMax);
        }
        return stocks;
    }

    // ==================== 内部：candidates 拼装 ====================

    /**
     * 为每只候选股拼装 OHLCV 历史 + meta + fundamentals。
     * <p>
     * 优化版（消除 N+1）：
     * <ol>
     *   <li>一次批量 SQL 取回所有候选股的近 {@link #SCREEN_HISTORY_BARS} 根 OHLCV；</li>
     *   <li>用 {@link CompletableFuture} 在 {@code screenerExecutor} 上并行做「DO 列表 -&gt; DTO」内存映射；</li>
     *   <li>join 合并，保持原 stocks 顺序与 key。</li>
     * </ol>
     */
    private Map<String, CandidateStockDTO> buildCandidates(List<StockBasicDO> stocks, String compactDate) {
        if (stocks.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> codes = stocks.stream().map(StockBasicDO::getTsCode).collect(Collectors.toList());

        // ① 一次批量 SQL 取回所有 OHLCV（ts_code -> 末 60 根升序列表）
        Map<String, List<DailyQuoteDO>> ohlcvMap = dailyQuoteService.queryRecentOhlcvByCodes(codes, ScreenerConstants.SCREEN_HISTORY_BARS);

        // ② 并行拼装：每只股的 DO->DTO 映射是 CPU/内存密集，可并行
        List<CompletableFuture<CandidateStockDTO>> futures = stocks.stream()
                .map(b -> CompletableFuture.supplyAsync(
                        () -> buildOneCandidate(b, ohlcvMap.getOrDefault(b.getTsCode(), Collections.emptyList()), compactDate),
                        screenerExecutor))
                .collect(Collectors.toList());

        // ③ join 合并，保持 stocks 顺序
        Map<String, CandidateStockDTO> candidates = new LinkedHashMap<>(stocks.size());
        for (int i = 0; i < stocks.size(); i++) {
            candidates.put(stocks.get(i).getTsCode(), futures.get(i).join());
        }
        return candidates;
    }

    /**
     * 单只候选股 DTO 构造（已传入预查好的 OHLCV + 选股日，不再触发 OHLCV 查询）。
     * <p>
     * 基本面从 daily_basic / fina_indicator 查（compactDate 当日可用数据），替换原空 Map。
     * meta 补真实 ST/停牌/涨跌停标记，替换原硬编码 false。
     */
    private CandidateStockDTO buildOneCandidate(StockBasicDO b, List<DailyQuoteDO> quotes, String compactDate) {
        CandidateStockDTO c = new CandidateStockDTO();

        // OHLCV 历史（已裁剪到 60 根，升序）
        if (quotes == null || quotes.isEmpty()) {
            c.setOhlcvHistory(Collections.emptyList());
        } else {
            List<OhlcvBarDTO> bars = new ArrayList<>(quotes.size());
            for (DailyQuoteDO q : quotes) {
                OhlcvBarDTO bar = new OhlcvBarDTO();
                bar.setDate(q.getTradeDate()); // 原样 YYYYMMDD
                bar.setOpen(q.getOpen());
                bar.setHigh(q.getHigh());
                bar.setLow(q.getLow());
                bar.setClose(q.getClose());
                bar.setVolume(q.getVol()); // daily_quote.vol -> engine volume
                // TODO 完整实现应走 KlineService 的前复权（preClose/adj_factor），此处用原始价
                bars.add(bar);
            }
            c.setOhlcvHistory(bars);
        }

        // 基本面：从 daily_basic + fina_indicator 查（补齐 fundamentals 缺口）
        c.setFundamentals(buildFundamentals(b.getTsCode(), compactDate));

        // meta：补真实 ST/停牌/涨跌停标记
        c.setMeta(buildMeta(b, quotes));
        return c;
    }

    /**
     * 组装基本面因子 Map（factorKey -&gt; value），缺数据则不放入（engine 侧缺失返回 NaN）。
     * <p>
     * daily_basic：PE_TTM / PB / PS_TTM / DV_RATIO / TOTAL_MV / CIRC_MV / TURNOVER_RATE；
     * fina_indicator：取「选股日已公告」的最近一期 ROE_TTM / ROA_TTM / GROSS_MARGIN / NETPROFIT_MARGIN /
     * REVENUE_YOY / PROFIT_YOY / DEBT_TO_ASSETS。
     */
    private Map<String, BigDecimal> buildFundamentals(String tsCode, String compactDate) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        if (tsCode == null || compactDate == null) {
            return m;
        }
        DailyBasicDO db = dailyBasicMapper.selectByCodeAndDate(tsCode, compactDate);
        if (db != null) {
            putIfNotNull(m, FactorKey.PE_TTM, db.getPeTtm());
            putIfNotNull(m, FactorKey.PB, db.getPb());
            putIfNotNull(m, FactorKey.PS_TTM, db.getPsTtm());
            putIfNotNull(m, FactorKey.DV_RATIO, db.getDvRatio());
            putIfNotNull(m, FactorKey.TOTAL_MV, db.getTotalMv());
            putIfNotNull(m, FactorKey.CIRC_MV, db.getCircMv());
            putIfNotNull(m, FactorKey.TURNOVER_RATE, db.getTurnoverRate());
        }
        FinaIndicatorDO fi = finaIndicatorMapper.selectLatestAnnouncedBefore(tsCode, compactDate);
        if (fi != null) {
            putIfNotNull(m, FactorKey.ROE_TTM, fi.getRoe());
            putIfNotNull(m, FactorKey.ROA_TTM, fi.getRoa());
            putIfNotNull(m, FactorKey.GROSS_MARGIN, fi.getGrossprofitMargin());
            putIfNotNull(m, FactorKey.NETPROFIT_MARGIN, fi.getNetprofitMargin());
            putIfNotNull(m, FactorKey.REVENUE_YOY, fi.getRevenueYoy());
            putIfNotNull(m, FactorKey.PROFIT_YOY, fi.getDtNetprofitYoy());
            putIfNotNull(m, FactorKey.DEBT_TO_ASSETS, fi.getDebtToAssets());
            putIfNotNull(m, FactorKey.EPS_YOY, fi.getEpsYoy());
        }
        return m;
    }

    private void putIfNotNull(Map<String, BigDecimal> m, String key, BigDecimal v) {
        if (v != null) {
            m.put(key, v);
        }
    }

    /**
     * 补真实 ST/停牌/涨跌停标记。
     * <p>
     * ST：stock_basic.name 含 "ST"；停牌：末根 vol=0；涨跌停：末根 pct_chg 简化判定（主板 9.9%，
     * 精确按板块阈值见 Phase 2）。
     */
    private CandidateMetaDTO buildMeta(StockBasicDO b, List<DailyQuoteDO> quotes) {
        CandidateMetaDTO meta = new CandidateMetaDTO();
        meta.setIndustry(b.getIndustry());
        meta.setListDate(toIsoDate(b.getListDate()));
        meta.setIsSt(b.getName() != null && b.getName().toUpperCase().contains(ScreenerConstants.ST_KEYWORD));
        meta.setIsSuspended(false);
        meta.setIsLimitUp(false);
        meta.setIsLimitDown(false);
        if (quotes != null && !quotes.isEmpty()) {
            DailyQuoteDO last = quotes.get(quotes.size() - 1);
            if (last.getVol() != null && last.getVol().signum() == 0) {
                meta.setIsSuspended(true);
            }
            BigDecimal pct = last.getPctChg();
            if (pct != null) {
                // 简化阈值：主板 9.9%（创业板/科创板 20%、ST 5% 的精确化留待 Phase 2）
                if (pct.compareTo(ScreenerConstants.LIMIT_UP_PCT) >= 0) {
                    meta.setIsLimitUp(true);
                } else if (pct.compareTo(ScreenerConstants.LIMIT_DOWN_PCT) <= 0) {
                    meta.setIsLimitDown(true);
                }
            }
        }
        return meta;
    }

    // ==================== 内部：落库 ====================

    @Transactional(rollbackFor = Exception.class)
    public Long persistResult(Long planId, String compactDate, SnapshotResultVO snapshot, JSONObject paramsSnapshot) {
        ScreenResultDO result = new ScreenResultDO();
        result.setPlanId(planId);
        result.setScreenDate(compactDate);
        result.setTotalCount(snapshot.getTotalCount() == null ? 0 : snapshot.getTotalCount());
        result.setStocksJson(snapshot.getStocks() == null
                ? "[]" : JSON.toJSONString(snapshot.getStocks()));
        result.setParamsJson(JSON.toJSONString(paramsSnapshot));
        screenResultMapper.insert(result);
        return result.getId();
    }

    // ==================== 内部：VO 转换 ====================

    private ScreenPlanVO toPlanVO(ScreenPlanDO plan) {
        if (plan == null) {
            return null;
        }
        ScreenPlanVO vo = new ScreenPlanVO();
        vo.setId(plan.getId());
        vo.setName(plan.getName());
        vo.setDescription(plan.getDescription());
        vo.setScreenConfig(parseConfig(plan.getScreenConfig()));
        vo.setCreatedAt(plan.getCreatedAt());
        vo.setUpdatedAt(plan.getUpdatedAt());
        return vo;
    }

    private ScreenResultVO toResultVO(ScreenResultDO r) {
        if (r == null) {
            return null;
        }
        ScreenResultVO vo = new ScreenResultVO();
        vo.setId(r.getId());
        vo.setPlanId(r.getPlanId());
        vo.setScreenDate(r.getScreenDate());
        vo.setTotalCount(r.getTotalCount());
        vo.setStocksJson(r.getStocksJson());
        vo.setParamsJson(r.getParamsJson());
        vo.setCreatedAt(r.getCreatedAt());
        return vo;
    }

    // ==================== 内部：工具 ====================

    private ScreenPlanDO requirePlan(Long id) {
        ScreenPlanDO plan = screenPlanMapper.selectById(id);
        if (plan == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "选股方案不存在");
        }
        return plan;
    }

    /**
     * 把 screen_config 文本解析为 JSONObject；空/非法时返回空对象。
     */
    private JSONObject parseConfig(String screenConfig) {
        if (screenConfig == null || screenConfig.isBlank()) {
            return new JSONObject();
        }
        try {
            Object parsed = JSON.parse(screenConfig);
            if (parsed instanceof JSONObject) {
                return (JSONObject) parsed;
            }
            return new JSONObject();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "方案配置 JSON 解析失败: " + e.getMessage());
        }
    }

    private String toIsoDate(String compact) {
        if (compact == null || compact.length() != ScreenerConstants.COMPACT_DATE_LENGTH) {
            return compact;
        }
        try {
            return LocalDate.parse(compact, ScreenerConstants.COMPACT_DATE).format(ScreenerConstants.ISO_DATE);
        } catch (Exception e) {
            return compact;
        }
    }

    private String toCompactDate(String iso) {
        if (iso == null || iso.length() != ScreenerConstants.ISO_DATE_LENGTH) {
            return iso;
        }
        try {
            return LocalDate.parse(iso, ScreenerConstants.ISO_DATE).format(ScreenerConstants.COMPACT_DATE);
        } catch (Exception e) {
            return iso;
        }
    }

    private String firstNonNull(String a, String b, String def) {
        return a != null ? a : (b != null ? b : def);
    }

    /**
     * 解析 screenConfig + 应用 req/overrides 覆盖，得到强类型 {@link ScreenResolution}。
     * <p>
     * 覆盖优先级：req.universe/date &gt; req.overrides.* &gt; config.* &gt; 默认值。
     * 替代旧版裸 JSONObject + 多重 firstNonNull/pick + instanceof Map 的脆弱合并。
     */
    private ScreenResolution resolve(JSONObject config, ScreenPlanRunRequestDTO req) {
        String universe = config.getString(ScreenConfigFields.UNIVERSE);
        String isoDate = config.getString(ScreenConfigFields.DATE);
        Object conditions = config.get(ScreenConfigFields.CONDITIONS);
        Object ranking = config.get(ScreenConfigFields.RANKING);
        FiltersDTO filters = config.getObject(ScreenConfigFields.FILTERS, FiltersDTO.class);
        Integer topN = config.getInteger(ScreenConfigFields.TOP_N);

        // req 顶层字段优先
        if (req != null) {
            if (req.getUniverse() != null) {
                universe = req.getUniverse();
            }
            if (req.getDate() != null) {
                isoDate = req.getDate();
            }
            // req.overrides 内部字段二次覆盖
            Object raw = req.getOverrides();
            if (raw instanceof Map<?, ?> rawMap) {
                JSONObject overrides = new JSONObject(rawMap);
                if (overrides.containsKey(ScreenConfigFields.UNIVERSE)) {
                    universe = overrides.getString(ScreenConfigFields.UNIVERSE);
                }
                if (overrides.containsKey(ScreenConfigFields.DATE)) {
                    isoDate = overrides.getString(ScreenConfigFields.DATE);
                }
                if (overrides.containsKey(ScreenConfigFields.CONDITIONS)) {
                    conditions = overrides.get(ScreenConfigFields.CONDITIONS);
                }
                if (overrides.containsKey(ScreenConfigFields.RANKING)) {
                    ranking = overrides.get(ScreenConfigFields.RANKING);
                }
                if (overrides.containsKey(ScreenConfigFields.FILTERS)) {
                    filters = overrides.getObject(ScreenConfigFields.FILTERS, FiltersDTO.class);
                }
                if (overrides.containsKey(ScreenConfigFields.TOP_N)) {
                    topN = overrides.getInteger(ScreenConfigFields.TOP_N);
                }
            }
        }

        // 默认值兜底
        universe = firstNonNull(universe, null, UniverseEnum.ALL_A_SHARES.getCode());
        isoDate = firstNonNull(isoDate, null, LocalDate.now().format(ScreenerConstants.ISO_DATE));

        return new ScreenResolution(universe, isoDate, conditions, ranking, filters, topN);
    }

    /**
     * 选股参数强类型解析结果（unresolved 字段保持 Object 以承载任意 conditions/ranking JSON 树）。
     */
    private record ScreenResolution(String universe, String isoDate, Object conditions,
                                    Object ranking, FiltersDTO filters, Integer topN) {
        /** 序列化为落库/缓存用的参数快照 JSON。 */
        JSONObject toParamsJson() {
            JSONObject o = new JSONObject(new LinkedHashMap<>());
            o.put(ScreenConfigFields.UNIVERSE, universe);
            o.put(ScreenConfigFields.DATE, isoDate);
            o.put(ScreenConfigFields.CONDITIONS, conditions);
            o.put(ScreenConfigFields.RANKING, ranking);
            o.put(ScreenConfigFields.FILTERS, filters);
            o.put(ScreenConfigFields.TOP_N, topN);
            return o;
        }
    }
}
