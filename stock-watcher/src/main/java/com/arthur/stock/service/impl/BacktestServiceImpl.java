package com.arthur.stock.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.client.BacktestClient;
import com.arthur.stock.constant.BacktestErrorCodes;
import com.arthur.stock.constant.BacktestModeEnum;
import com.arthur.stock.constant.BacktestStatusEnum;
import com.arthur.stock.constant.ExchangeEnum;
import com.arthur.stock.constant.StrategySchemaConstants;
import com.arthur.stock.constant.StrategyStatusEnum;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.backtest.BacktestCompareVO;
import com.arthur.stock.dto.backtest.BacktestReportVO;
import com.arthur.stock.dto.backtest.BacktestRunRequestDTO;
import com.arthur.stock.dto.backtest.BacktestTaskVO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.mapper.DailyBasicMapper;
import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.mapper.QuantBacktestMapper;
import com.arthur.stock.mapper.QuantBacktestReportMapper;
import com.arthur.stock.mapper.QuantStrategyMapper;
import com.arthur.stock.mapper.QuantStrategyVersionMapper;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.model.DailyBasicDO;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.model.QuantBacktestDO;
import com.arthur.stock.model.QuantBacktestReportDO;
import com.arthur.stock.model.QuantStrategyDO;
import com.arthur.stock.model.QuantStrategyVersionDO;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.model.StockNamechangeDO;
import com.arthur.stock.model.StockStkLimitDO;
import com.arthur.stock.model.TradeCalDO;
import com.arthur.stock.service.BacktestService;
import com.arthur.stock.service.IndexWeightService;
import com.arthur.stock.service.StockNamechangeService;
import com.arthur.stock.service.StockSuspendDService;
import com.arthur.stock.service.StockStkLimitService;
import com.arthur.stock.service.SwIndustryService;
import com.arthur.stock.service.TradeCalService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 回测中心编排服务实现（spec 007 T3）。
 * <p>
 * <b>编排链路</b>（{@link #run}）：
 * <ol>
 *   <li>模式校验（非 SINGLE → MODE_NOT_SUPPORTED）。</li>
 *   <li>策略 + 版本查询，版本状态校验（DRAFT/ARCHIVED 不可回测）。</li>
 *   <li>范式校验（config_json 含 trading_config.rebalance 或 exit.rules → 不支持）。</li>
 *   <li>benchmark 解析（overrideConfig > config.backtest_config.benchmark > 默认 000300.SH）。</li>
 *   <li>落 PENDING 任务（同步返回 VO）。</li>
 *   <li>异步：PENDING→RUNNING → 拼 kline_data/benchmark_data → 调 engine → 落 report + SUCCESS；
 *       异常 → FAILED + error_message 截断 1024。</li>
 * </ol>
 * <p>
 * <b>异步模型</b>：不在方法上加 {@code @Async}，而是显式 {@code backtestExecutor.execute(...)}，
 * 与 {@code ScreenerAsyncConfig} 模式一致，保持 {@code run} 同步返回 PENDING VO 的契约。
 * <p>
 * <b>用户上下文</b>：{@code UserContext} 基于 ThreadLocal，异步线程不可读，故 {@code currentUser}
 * 由 Controller 在调用前显式传入。
 */
@Slf4j
@Service
public class BacktestServiceImpl implements BacktestService {

    /** 默认基准（沪深300） */
    private static final String DEFAULT_BENCHMARK = "000300.SH";

    /** error_message 截断上限 */
    private static final int MAX_ERROR_MSG_LEN = 1024;

    /** 候选基准白名单（code→name） */
    private static final List<Map<String, String>> BENCHMARK_WHITELIST = List.of(
            Map.of("code", "000300.SH", "name", "沪深300"),
            Map.of("code", "000905.SH", "name", "中证500"),
            Map.of("code", "000016.SH", "name", "上证50"),
            Map.of("code", "000852.SH", "name", "中证1000")
    );

    private final QuantBacktestMapper backtestMapper;
    private final QuantBacktestReportMapper reportMapper;
    private final QuantStrategyMapper strategyMapper;
    private final QuantStrategyVersionMapper versionMapper;
    private final DailyQuoteMapper dailyQuoteMapper;
    private final DailyBasicMapper dailyBasicMapper;
    private final BacktestClient backtestClient;
    private final Executor backtestExecutor;
    private final IndexWeightService indexWeightService;
    private final SwIndustryService swIndustryService;
    private final TradeCalService tradeCalService;
    private final StockNamechangeService stockNamechangeService;
    private final StockSuspendDService stockSuspendDService;
    private final StockStkLimitService stockStkLimitService;
    private final StockBasicMapper stockBasicMapper;

    public BacktestServiceImpl(QuantBacktestMapper backtestMapper,
                               QuantBacktestReportMapper reportMapper,
                               QuantStrategyMapper strategyMapper,
                               QuantStrategyVersionMapper versionMapper,
                               DailyQuoteMapper dailyQuoteMapper,
                               DailyBasicMapper dailyBasicMapper,
                               BacktestClient backtestClient,
                               @Qualifier("backtestExecutor") Executor backtestExecutor,
                               IndexWeightService indexWeightService,
                               SwIndustryService swIndustryService,
                               TradeCalService tradeCalService,
                               StockNamechangeService stockNamechangeService,
                               StockSuspendDService stockSuspendDService,
                               StockStkLimitService stockStkLimitService,
                               StockBasicMapper stockBasicMapper) {
        this.backtestMapper = backtestMapper;
        this.reportMapper = reportMapper;
        this.strategyMapper = strategyMapper;
        this.versionMapper = versionMapper;
        this.dailyQuoteMapper = dailyQuoteMapper;
        this.dailyBasicMapper = dailyBasicMapper;
        this.backtestClient = backtestClient;
        this.backtestExecutor = backtestExecutor;
        this.indexWeightService = indexWeightService;
        this.swIndustryService = swIndustryService;
        this.tradeCalService = tradeCalService;
        this.stockNamechangeService = stockNamechangeService;
        this.stockSuspendDService = stockSuspendDService;
        this.stockStkLimitService = stockStkLimitService;
        this.stockBasicMapper = stockBasicMapper;
    }

    // ==================== run ====================

    @Override
    public BacktestTaskVO run(BacktestRunRequestDTO req, String currentUser) {
        // 1. 模式校验：第一波仅 SINGLE
        String mode = req.getMode() == null ? BacktestModeEnum.SINGLE.getCode() : req.getMode();
        if (!BacktestModeEnum.SINGLE.getCode().equals(mode)) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_MODE_NOT_SUPPORTED,
                    "第一波仅支持 SINGLE 模式，当前模式: " + mode);
        }

        // 2. 查策略（前端传 uuid，后端查主表拿 PK id）
        QuantStrategyDO strategy = requireStrategyByUuid(req.getUuid());

        // 3. 解析版本号（未传取 currentVersion）+ 查版本快照
        Integer versionNo = req.getVersionNo() != null ? req.getVersionNo() : strategy.getCurrentVersion();
        if (versionNo == null) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_STRATEGY_VERSION_INVALID, "策略无可用版本");
        }
        QuantStrategyVersionDO version = loadVersion(strategy.getId(), versionNo);

        // 4. 版本状态校验：DRAFT/ARCHIVED 不可回测
        // 注：状态记录在 strategy 主表（status 字段），version 表无独立 status。
        // 这里用 strategy.status 判定；非 VERIFIED/ACTIVE 视为不可回测。
        StrategyStatusEnum statusEnum = StrategyStatusEnum.fromCode(strategy.getStatus());
        if (statusEnum == StrategyStatusEnum.DRAFT || statusEnum == StrategyStatusEnum.ARCHIVED) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_STRATEGY_VERSION_INVALID,
                    "策略状态 " + strategy.getStatus() + " 不可回测，需先验证/激活");
        }

        // 5. 范式校验：config_json 含 trading_config.rebalance 或 exit.rules
        JSONObject configJson = parseJsonSafely(version.getConfigJson());
        // 清洗存量配置的废弃字段（scope / trading_config.symbols），避免 engine extra=forbid 报错
        configJson.remove("scope");
        JSONObject tradingCfg = configJson.getJSONObject("trading_config");
        if (tradingCfg != null) {
            tradingCfg.remove("symbols");
        }
        checkParadigmSupported(configJson);

        // 6. benchmark 解析
        String benchmark = resolveBenchmark(req, configJson);

        // 7. 落 PENDING 任务
        String now = Instant.now().toString();
        String overrideConfigStr = req.getOverrideConfig() == null ? null : JSON.toJSONString(req.getOverrideConfig());
        QuantBacktestDO task = new QuantBacktestDO();
        task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        task.setStrategyId(strategy.getId());
        task.setVersionNo(versionNo);
        task.setMode(mode);
        task.setStatus(BacktestStatusEnum.PENDING.getCode());
        task.setProgress(0);
        task.setOverrideConfig(overrideConfigStr);
        task.setBenchmark(benchmark);
        task.setCreatedBy(currentUser);
        task.setCreatedAt(now);
        backtestMapper.insert(task);

        // 8. 异步执行（捕获所有异常，落 FAILED）
        // 提前捕获不可变参数，避免异步线程里重复查库与 ThreadLocal 失效
        final Long taskId = task.getId();
        final String taskUuid = task.getTaskId();
        final JSONObject finalConfig = configJson;
        final String finalBenchmark = benchmark;
        final String finalOverrideConfig = overrideConfigStr;
        final Long strategyPkId = strategy.getId();
        backtestExecutor.execute(() -> executeAsync(taskId, taskUuid, finalConfig, finalBenchmark,
                finalOverrideConfig, strategyPkId));

        return toTaskVO(task);
    }

    /**
     * 异步执行回测：PENDING→RUNNING → 拼 kline/benchmark → 调 engine → 落 report + SUCCESS。
     * 任何异常都落 FAILED + error_message 截断 1024。
     */
    private void executeAsync(Long taskId, String taskUuid, JSONObject configJson,
                              String benchmark, String overrideConfigStr, Long strategyPkId) {
        String startedAt = Instant.now().toString();
        try {
            // PENDING → RUNNING
            QuantBacktestDO running = new QuantBacktestDO();
            running.setId(taskId);
            running.setStatus(BacktestStatusEnum.RUNNING.getCode());
            running.setProgress(10);
            running.setStartedAt(startedAt);
            backtestMapper.updateById(running);

            // 拼 kline_data
            JSONObject klineData = buildKlineData(configJson);
            if (klineData == null || klineData.isEmpty()) {
                throw new BusinessException(BacktestErrorCodes.BACKTEST_DATA_INSUFFICIENT,
                        "未找到策略标的的日线行情数据");
            }

            // 拼 benchmark_data（查空降级为 null，warn 不阻断）
            JSONObject benchmarkData = buildBenchmarkData(benchmark);
            if (benchmarkData == null) {
                log.warn("回测 {} 基准 {} 行情为空，回测将以无基准曲线方式继续", taskUuid, benchmark);
            }

            // 拼 engine 请求体
            JSONObject engineReq = new JSONObject();
            engineReq.put("mode", BacktestModeEnum.SINGLE.getCode());
            // engine 协议字段 strategyId：传策略主表 PK（Long），保证 engine 侧用主键索引
            engineReq.put("strategyId", strategyPkId);
            engineReq.put("config", configJson);
            if (overrideConfigStr != null && !overrideConfigStr.isBlank()) {
                engineReq.put("override_config", JSON.parseObject(overrideConfigStr));
            }
            engineReq.put("benchmark", benchmark);
            engineReq.put("kline_data", klineData);
            engineReq.put("benchmark_data", benchmarkData);

            // 调 engine
            JSONObject result = backtestClient.runSingle(engineReq);

            // 落 report
            persistReport(taskId, result, benchmarkData);

            // 更新 SUCCESS
            QuantBacktestDO done = new QuantBacktestDO();
            done.setId(taskId);
            done.setStatus(BacktestStatusEnum.SUCCESS.getCode());
            done.setProgress(100);
            done.setFinishedAt(Instant.now().toString());
            backtestMapper.updateById(done);
            log.info("回测 {} 成功完成", taskUuid);

        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            msg = truncate(msg);
            QuantBacktestDO failed = new QuantBacktestDO();
            failed.setId(taskId);
            failed.setStatus(BacktestStatusEnum.FAILED.getCode());
            failed.setProgress(0);
            failed.setErrorMessage(msg);
            failed.setFinishedAt(Instant.now().toString());
            backtestMapper.updateById(failed);
            log.error("回测 {} 失败: {}", taskUuid, msg, e);
        }
    }

    // ==================== listTasks ====================

    @Override
    public PageResult<BacktestTaskVO> listTasks(int page, int size, String uuid, String status,
                                                 String startDate, String endDate) {
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : size;

        LambdaQueryWrapper<QuantBacktestDO> qw = new LambdaQueryWrapper<>();
        // 前端传策略 uuid，内部转主表 PK id 再查回测表
        if (uuid != null && !uuid.isBlank()) {
            QuantStrategyDO strategy = strategyMapper.selectOne(
                    new LambdaQueryWrapper<QuantStrategyDO>().eq(QuantStrategyDO::getUuid, uuid));
            if (strategy == null) {
                // 策略不存在，记录 warn 便于排错（前端看到空列表无法定位原因）
                log.warn("listTasks: 策略 uuid={} 不存在，返回空列表", uuid);
                return PageResult.of(Collections.emptyList(), 0L, p, s);
            }
            qw.eq(QuantBacktestDO::getStrategyId, strategy.getId());
        }
        if (status != null && !status.isBlank()) {
            qw.eq(QuantBacktestDO::getStatus, status);
        }
        if (startDate != null && !startDate.isBlank()) {
            qw.ge(QuantBacktestDO::getCreatedAt, startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            qw.le(QuantBacktestDO::getCreatedAt, endDate);
        }
        qw.orderByDesc(QuantBacktestDO::getId);

        Page<QuantBacktestDO> pg = backtestMapper.selectPage(new Page<>(p, s), qw);

        // 批量预加载本页所有策略主表，避免 toTaskVO 内逐行 selectById 产生 N+1 查询
        Map<Long, QuantStrategyDO> strategyMap = batchLoadStrategies(pg.getRecords());
        List<BacktestTaskVO> list = pg.getRecords().stream()
                .map(t -> toTaskVO(t, strategyMap))
                .toList();
        return PageResult.of(list, pg.getTotal(), p, s);
    }

    // ==================== getTask / cancelTask / rerunTask ====================

    @Override
    public BacktestTaskVO getTask(String taskId) {
        return toTaskVO(requireTaskByUuid(taskId));
    }

    @Override
    public BacktestTaskVO getTaskById(Long backtestId) {
        if (backtestId == null) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_NOT_FOUND, "backtestId 不能为空");
        }
        QuantBacktestDO task = backtestMapper.selectById(backtestId);
        if (task == null) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_NOT_FOUND, "回测任务不存在: " + backtestId);
        }
        return toTaskVO(task);
    }

    @Override
    public BacktestTaskVO cancelTask(String taskId) {
        QuantBacktestDO task = requireTaskByUuid(taskId);
        BacktestStatusEnum st = BacktestStatusEnum.fromCode(task.getStatus());
        if (st == null || !st.canCancel()) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_TASK_STATUS_CONFLICT,
                    "任务状态 " + task.getStatus() + " 不可取消（仅 PENDING 可取消）");
        }
        task.setStatus(BacktestStatusEnum.CANCELLED.getCode());
        task.setFinishedAt(Instant.now().toString());
        backtestMapper.updateById(task);
        return toTaskVO(task);
    }

    @Override
    public BacktestTaskVO rerunTask(String taskId, String currentUser) {
        QuantBacktestDO origin = requireTaskByUuid(taskId);
        // origin.strategyId 是主表 PK（Long），需反查主表拿 uuid 填入请求
        QuantStrategyDO originStrategy = strategyMapper.selectById(origin.getStrategyId());
        if (originStrategy == null) {
            // 原策略被物理删除（理论上 ARCHIVED 软删 PK 仍在），抛 404 避免下游报「策略ID不能为空」难以定位
            throw new BusinessException(BacktestErrorCodes.BACKTEST_STRATEGY_VERSION_INVALID,
                    "原策略不存在（strategyId=" + origin.getStrategyId() + "），无法重跑");
        }
        BacktestRunRequestDTO req = new BacktestRunRequestDTO();
        req.setMode(origin.getMode());
        req.setUuid(originStrategy.getUuid());
        req.setVersionNo(origin.getVersionNo());
        req.setBenchmark(origin.getBenchmark());
        if (origin.getOverrideConfig() != null && !origin.getOverrideConfig().isBlank()) {
            try {
                req.setOverrideConfig(JSON.parseObject(origin.getOverrideConfig()));
            } catch (Exception e) {
                log.warn("原任务 overrideConfig 解析失败，忽略: {}", e.getMessage());
            }
        }
        return run(req, currentUser);
    }

    // ==================== deleteBacktest ====================

    @Override
    public void deleteBacktest(Long backtestId) {
        QuantBacktestDO task = backtestMapper.selectById(backtestId);
        if (task == null) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_NOT_FOUND, "回测任务不存在: " + backtestId);
        }
        // 删 report（可能有也可能没有）
        reportMapper.delete(new LambdaQueryWrapper<QuantBacktestReportDO>()
                .eq(QuantBacktestReportDO::getBacktestId, backtestId));
        backtestMapper.deleteById(backtestId);
    }

    // ==================== getReport ====================

    @Override
    public BacktestReportVO getReport(Long backtestId) {
        QuantBacktestDO task = backtestMapper.selectById(backtestId);
        if (task == null) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_NOT_FOUND, "回测任务不存在: " + backtestId);
        }
        QuantBacktestReportDO report = reportMapper.selectOne(
                new LambdaQueryWrapper<QuantBacktestReportDO>()
                        .eq(QuantBacktestReportDO::getBacktestId, backtestId));
        if (report == null) {
            // 任务存在但 report 缺失（未 SUCCESS 或被清理）
            throw new BusinessException(BacktestErrorCodes.BACKTEST_NOT_FOUND,
                    "回测报告不存在，任务状态: " + task.getStatus());
        }
        return toReportVO(report);
    }

    // ==================== compare ====================

    @Override
    public BacktestCompareVO compare(List<Long> ids) {
        if (ids == null || ids.size() < 2) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_COMPARE_FAILED, "对比至少需要 2 个任务");
        }
        // 查所有 report
        List<QuantBacktestReportDO> reports = new ArrayList<>();
        for (Long id : ids) {
            QuantBacktestReportDO r = reportMapper.selectOne(
                    new LambdaQueryWrapper<QuantBacktestReportDO>()
                            .eq(QuantBacktestReportDO::getBacktestId, id));
            if (r == null) {
                throw new BusinessException(BacktestErrorCodes.BACKTEST_COMPARE_FAILED,
                        "任务 " + id + " 无报告，无法对比");
            }
            reports.add(r);
        }

        BacktestCompareVO vo = new BacktestCompareVO();
        // 曲线
        List<Map<String, Object>> curves = new ArrayList<>();
        for (int i = 0; i < reports.size(); i++) {
            QuantBacktestReportDO r = reports.get(i);
            Map<String, Object> curve = new LinkedHashMap<>();
            curve.put("label", "BT-" + ids.get(i));
            curve.put("backtestId", ids.get(i));
            curve.put("equityCurve", parseMap(r.getEquityCurveJson()));
            curves.add(curve);
        }
        vo.setCurves(curves);

        // 指标对比表
        vo.setMetricsTable(buildMetricsTable(reports, ids));

        // 雷达图
        vo.setRadarData(buildRadarData(reports, ids));
        return vo;
    }

    private List<Map<String, Object>> buildMetricsTable(List<QuantBacktestReportDO> reports, List<Long> ids) {
        // 收集所有指标 key（保留首次出现顺序）
        List<String> keys = new ArrayList<>();
        List<Map<String, Object>> metricMaps = new ArrayList<>();
        for (QuantBacktestReportDO r : reports) {
            Map<String, Object> m = parseMap(r.getMetricsJson());
            metricMaps.add(m);
            for (String k : m.keySet()) {
                if (!keys.contains(k)) {
                    keys.add(k);
                }
            }
        }
        List<Map<String, Object>> table = new ArrayList<>();
        for (String k : keys) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metric", k);
            List<Object> values = new ArrayList<>();
            for (Map<String, Object> m : metricMaps) {
                values.add(m.get(k));
            }
            row.put("values", values);
            table.add(row);
        }
        return table;
    }

    private List<Map<String, Object>> buildRadarData(List<QuantBacktestReportDO> reports, List<Long> ids) {
        // 雷达维度：total_return_pct / sharpe_ratio / max_drawdown_pct / win_rate / profit_factor
        // 归一化到 0~1（每维度按所有任务的最大绝对值缩放）
        List<String> dims = Arrays.asList(
                "total_return_pct", "sharpe_ratio", "calmar_ratio", "win_rate", "profit_factor");
        List<Map<String, Object>> raw = new ArrayList<>();
        for (QuantBacktestReportDO r : reports) {
            raw.add(parseMap(r.getMetricsJson()));
        }
        // 计算每维度最大绝对值
        Map<String, Double> maxAbs = new HashMap<>();
        for (String d : dims) {
            double max = 0.0;
            for (Map<String, Object> m : raw) {
                Double v = toDouble(m.get(d));
                if (v != null) {
                    max = Math.max(max, Math.abs(v));
                }
            }
            maxAbs.put(d, max == 0.0 ? 1.0 : max);
        }
        List<Map<String, Object>> radar = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> m = raw.get(i);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("label", "BT-" + ids.get(i));
            Map<String, Object> values = new LinkedHashMap<>();
            for (String d : dims) {
                Double v = toDouble(m.get(d));
                double norm = v == null ? 0.0 : Math.abs(v) / maxAbs.get(d);
                values.put(d, Math.round(norm * 1000) / 1000.0);
            }
            point.put("values", values);
            radar.add(point);
        }
        return radar;
    }

    // ==================== listBenchmarks / getConstants ====================

    @Override
    public List<Map<String, String>> listBenchmarks() {
        return BENCHMARK_WHITELIST;
    }

    @Override
    public Object getConstants() {
        try {
            return backtestClient.getConstants();
        } catch (Exception e) {
            log.warn("代理 engine /constants 失败，返回 watcher 默认常量: {}", e.getMessage());
            // 兜底默认值
            JSONObject fallback = new JSONObject();
            fallback.put("brokerProfiles", Arrays.asList("cn_stock_miniqmt", "cn_stock_t1_low_fee"));
            fallback.put("sortMetrics", Arrays.asList(
                    "sharpe_ratio", "total_return_pct", "max_drawdown_pct",
                    "sortino_ratio", "calmar_ratio", "win_rate", "profit_factor"));
            fallback.put("paradigmsSupported", Collections.singletonList(BacktestModeEnum.SINGLE.getCode()));
            return fallback;
        }
    }

    /**
     * spec 015 FR-O1：为参数寻优装配上下文（configJson + kline_data）。
     * <p>
     * 复用 run() 的 configJson 加载 + 范式校验 + buildKlineData，但不落任务表、不异步执行。
     * OptimizeController 拿到后透传给 engine {@code /optimize} / {@code /walk_forward}。
     * 网格范式（method=grid）无 tunable_params，此处仍返回 config + kline，
     * 由 engine validator 报 TUNABLE_PARAM_INVALID（param_grid 为空）。
     */
    @Override
    public JSONObject buildOptimizeContext(String uuid, Integer versionNo) {
        // 1. 查策略 + 版本
        QuantStrategyDO strategy = requireStrategyByUuid(uuid);
        Integer ver = versionNo != null ? versionNo : strategy.getCurrentVersion();
        if (ver == null) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_STRATEGY_VERSION_INVALID, "策略无可用版本");
        }
        QuantStrategyVersionDO version = loadVersion(strategy.getId(), ver);

        // 2. 解析 configJson + 清洗废弃字段
        JSONObject configJson = parseJsonSafely(version.getConfigJson());
        configJson.remove("scope");
        JSONObject tradingCfg = configJson.getJSONObject("trading_config");
        if (tradingCfg != null) {
            tradingCfg.remove("symbols");
        }
        // 范式校验沿用现有（signals/rebalance/grid 三范式 engine 侧已支持）
        checkParadigmSupported(configJson);

        // 3. 拼 kline_data（与 run() 同口径）
        JSONObject klineData = buildKlineData(configJson);
        if (klineData == null || klineData.isEmpty()) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_DATA_INSUFFICIENT,
                    "未找到策略标的的日线行情数据");
        }

        // 4. 返回上下文
        JSONObject ctx = new JSONObject();
        ctx.put("config", configJson);
        ctx.put("kline_data", klineData);
        return ctx;
    }

    // ==================== 重启恢复 ====================

    /**
     * 重启恢复：把 PENDING/RUNNING 重置为 FAILED，error_message="引擎中断，请重跑"。
     * <p>
     * 应用启动时执行，避免遗留任务卡在中间态（engine 进程已不在）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resetStaleTasks() {
        List<QuantBacktestDO> stale = backtestMapper.selectList(
                new LambdaQueryWrapper<QuantBacktestDO>()
                        .in(QuantBacktestDO::getStatus,
                                BacktestStatusEnum.PENDING.getCode(),
                                BacktestStatusEnum.RUNNING.getCode()));
        if (stale.isEmpty()) {
            return;
        }
        String now = Instant.now().toString();
        for (QuantBacktestDO t : stale) {
            t.setStatus(BacktestStatusEnum.FAILED.getCode());
            t.setErrorMessage("引擎中断，请重跑");
            t.setFinishedAt(now);
            backtestMapper.updateById(t);
        }
        log.warn("重启恢复：重置 {} 个 PENDING/RUNNING 任务为 FAILED", stale.size());
    }

    // ==================== 内部：数据拼装 ====================

    /**
     * 从 config_json 解析回测标的集合，查 daily_quote 组装 {symbol: [{date, open, high, low, close, volume}]}。
     * <p>
     * 标的决定规则：
     * <ul>
     *   <li>screen_config.universe.pool=manual：用 screen_config.universe.stocks；</li>
     *   <li>screen_config.universe.pool=csi300/csi500：取回测区间内所有曾入选该指数的成分股并集（防幸存者偏差）；</li>
     *   <li>screen_config.universe.pool=all_a_shares：Phase 2 直接在 resolveBacktestSymbols 抛 BACKTEST_UNIVERSE_TOO_LARGE，不进入本方法后续拼装。</li>
     * </ul>
     * 全空返回 null（调用方据此抛 DATA_INSUFFICIENT）。
     */
    private JSONObject buildKlineData(JSONObject configJson) {
        if (configJson == null) {
            return null;
        }
        List<String> symbols = resolveBacktestSymbols(configJson);
        if (symbols.isEmpty()) {
            return null;
        }
        // 回测区间从 backtest_config.start_date/end_date（yyyyMMdd）取，留空表示全部历史；
        // 与 csi300/csi500 成分股区间口径一致，daily_basic 按此区间批量拉取，避免全表扫描。
        JSONObject bt = configJson.getJSONObject("backtest_config");
        String startDate = bt == null ? null : normalizeDate(bt.getString("start_date"));
        String endDate = bt == null ? null : normalizeDate(bt.getString("end_date"));

        JSONObject kline = new JSONObject();
        // spec 013 遗留#3：行业归属 point-in-time（按 trade_date 取当时归属，消除跨年 lookahead bias）
        // 批量预查每只标的在区间内每日生效的申万一级行业（key=tsCode, value={trade_date -> index_code}）；
        // 未匹配到（如未同步申万数据）的 tsCode 不在 Map 中，对应 bar 不下发 sw_industry_l1。
        Map<String, Map<String, String>> swL1PitMap = swIndustryService.getL1IndustriesPit(symbols, startDate, endDate);
        // 批量预查回测区间内的调仓标记（周/月/季 first/last），key=cal_date(yyyyMMdd)，
        // 供 engine 调仓日判定（spec 011 P2-1：从自然日 day_of_period 改为 trade_cal 预计算标记）。
        // 仅交易日有记录；非交易日（或区间外）的 bar 不下发标记字段，engine 侧按缺省处理。
        // 用 SSE 标准交易日历（A 股两交易所交易日基本一致）。
        Map<String, TradeCalDO> rebalanceFlags = tradeCalService.queryFlagsByRange(
                ExchangeEnum.SSE.getCode(), startDate, endDate);
        // 批量预查 5 个元数据字段（spec 013 遗留#1）：namechange / suspend_d / stk_limit / list_date
        Map<String, List<StockNamechangeDO>> namechangeMap = stockNamechangeService.listByTsCodes(symbols);
        Map<String, Set<String>> suspendSet = stockSuspendDService.listSuspendDates(symbols, startDate, endDate);
        Map<String, Map<String, StockStkLimitDO>> limitMap = stockStkLimitService.listByRange(symbols, startDate, endDate);
        // list_date 静态字段（stock_basic），按 ts_code 取一次
        Map<String, String> listDateMap = stockBasicMapper.selectList(
                new LambdaQueryWrapper<StockBasicDO>().in(StockBasicDO::getTsCode, symbols))
                .stream().collect(Collectors.toMap(StockBasicDO::getTsCode,
                        b -> b.getListDate() == null ? "" : b.getListDate(), (a, b) -> a));
        for (String code : symbols) {
            // spec 015 修复（老股民审查 Top2）：daily_quote 查询加 between(start_date, end_date)，
            // 避免寻优/回测拉全历史 K 线导致 HTTP 载荷过大 + engine 多进程 pickle OOM。
            // startDate/endDate 为空时（normalizeDate 返回 null）回退全量，保持旧行为。
            List<DailyQuoteDO> quotes;
            if (startDate != null && endDate != null) {
                quotes = dailyQuoteMapper.selectList(
                        new LambdaQueryWrapper<DailyQuoteDO>()
                                .eq(DailyQuoteDO::getTsCode, code)
                                .between(DailyQuoteDO::getTradeDate, startDate, endDate)
                                .orderByAsc(DailyQuoteDO::getTradeDate));
            } else {
                quotes = dailyQuoteMapper.selectList(
                        new LambdaQueryWrapper<DailyQuoteDO>()
                                .eq(DailyQuoteDO::getTsCode, code)
                                .orderByAsc(DailyQuoteDO::getTradeDate));
            }
            if (quotes.isEmpty()) {
                continue;
            }
            // 拉取同区间基本面（估值/换手率/市值），按 trade_date 建索引便于逐 bar join。
            // daily_basic 缺记录的日期，bar 不含基本面字段（engine 侧 NaN 兜底）。
            Map<String, DailyBasicDO> basicMap = dailyBasicMapper
                    .selectByCodeAndDateRange(code, startDate, endDate)
                    .stream()
                    .collect(Collectors.toMap(DailyBasicDO::getTradeDate, b -> b, (a, b) -> a));

            JSONArray arr = new JSONArray(quotes.size());
            for (DailyQuoteDO q : quotes) {
                JSONObject bar = new JSONObject();
                bar.put("date", q.getTradeDate());
                bar.put("open", toDouble(q.getOpen()));
                bar.put("high", toDouble(q.getHigh()));
                bar.put("low", toDouble(q.getLow()));
                bar.put("close", toDouble(q.getClose()));
                bar.put("volume", toDouble(q.getVol()));
                // spec 013 遗留#3：PIT 行业归属（按 bar 的 trade_date 取当日生效的一级行业）
                Map<String, String> perCodePit = swL1PitMap.get(code);
                if (perCodePit != null) {
                    String swL1Td = perCodePit.get(q.getTradeDate());
                    if (swL1Td != null) {
                        bar.put("sw_industry_l1", swL1Td);
                    }
                }
                // 调仓标记（spec 011 P2-1）：按 bar 的 trade_date 从预查的 trade_cal 标记注入。
                // 仅在命中交易日记录时下发；engine 侧据 is_first_of_week/..._month/..._quarter
                // 结合 trigger(first/last) 判定调仓日，取代自然日 day_of_period。
                TradeCalDO calFlags = rebalanceFlags.get(q.getTradeDate());
                if (calFlags != null) {
                    bar.put("is_first_of_week", calFlags.getIsFirstOfWeek());
                    bar.put("is_last_of_week", calFlags.getIsLastOfWeek());
                    bar.put("is_first_of_month", calFlags.getIsFirstOfMonth());
                    bar.put("is_last_of_month", calFlags.getIsLastOfMonth());
                    bar.put("is_first_of_quarter", calFlags.getIsFirstOfQuarter());
                    bar.put("is_last_of_quarter", calFlags.getIsLastOfQuarter());
                }
                // spec 013 遗留#1：5 个元数据字段逐日准确下发
                String td = q.getTradeDate();
                // is_st：namechange 该日生效的 name 含 "ST"
                List<StockNamechangeDO> ncList = namechangeMap.get(code);
                String activeName = activeNameAt(ncList, td);
                bar.put("is_st", (activeName != null && activeName.contains("ST")) ? "1" : "0");
                // is_suspended：当日是否在停牌集合
                Set<String> suspDates = suspendSet.getOrDefault(code, Collections.emptySet());
                bar.put("is_suspended", suspDates.contains(td) ? "1" : "0");
                // is_limit_up / is_limit_down：按 stk_limit 精确判定
                Map<String, StockStkLimitDO> codeLimit = limitMap.getOrDefault(code, Collections.emptyMap());
                StockStkLimitDO lim = codeLimit.get(td);
                if (lim != null) {
                    double close = toDouble(q.getClose());
                    Double up = lim.getUpLimit();
                    Double down = lim.getDownLimit();
                    if (up != null && close >= up) {
                        bar.put("is_limit_up", "1");
                    } else {
                        bar.put("is_limit_up", "0");
                    }
                    if (down != null && close <= down) {
                        bar.put("is_limit_down", "1");
                    } else {
                        bar.put("is_limit_down", "0");
                    }
                }
                // list_date：stock_basic 静态字段
                String ld = listDateMap.get(code);
                if (ld != null && !ld.isEmpty()) {
                    bar.put("list_date", ld);
                }
                // 追加基本面字段（字段名与 daily_basic 表下划线列名对齐，便于 engine 侧统一口径）
                DailyBasicDO basic = basicMap.get(q.getTradeDate());
                if (basic != null) {
                    appendBasicFields(bar, basic);
                }
                arr.add(bar);
            }
            kline.put(code, arr);
        }
        return kline.isEmpty() ? null : kline;
    }

    /**
     * 取 namechange 列表中在 tradeDate 生效的最新 name（start_date<=td 且 (end_date 空或 >=td)，按 start_date 倒序首条）。
     * <p>
     * 用于判定某日某标的是否 ST（name 含 "ST"）。list 为 null/空或无生效记录 → null。
     */
    private static String activeNameAt(List<StockNamechangeDO> list, String tradeDate) {
        if (list == null || list.isEmpty() || tradeDate == null) {
            return null;
        }
        // selectByTsCodes 已按 ts_code, start_date 升序返回；倒序遍历找首条生效
        for (int i = list.size() - 1; i >= 0; i--) {
            StockNamechangeDO nc = list.get(i);
            String start = nc.getStartDate();
            String end = nc.getEndDate();
            if (start == null || start.compareTo(tradeDate) > 0) {
                continue;
            }
            boolean active = (end == null || end.isEmpty() || end.compareTo(tradeDate) >= 0);
            if (active) {
                return nc.getName();
            }
        }
        return null;
    }

    /**
     * 把单日基本面（DailyBasicDO）的估值/换手率/市值字段平铺到 bar dict。
     * <p>
     * 字段名采用 daily_basic 表的下划线列名（pe/pe_ttm/pb/total_mv 等），
     * 与 engine 选股/因子计算口径保持一致；缺失或为 null 的字段不写入（engine 侧 NaN 兜底）。
     */
    private static void appendBasicFields(JSONObject bar, DailyBasicDO b) {
        putIfNotNull(bar, "pe", toDouble(b.getPe()));
        putIfNotNull(bar, "pe_ttm", toDouble(b.getPeTtm()));
        putIfNotNull(bar, "pb", toDouble(b.getPb()));
        putIfNotNull(bar, "ps", toDouble(b.getPs()));
        putIfNotNull(bar, "ps_ttm", toDouble(b.getPsTtm()));
        putIfNotNull(bar, "dv_ratio", toDouble(b.getDvRatio()));
        putIfNotNull(bar, "dv_ttm", toDouble(b.getDvTtm()));
        putIfNotNull(bar, "total_mv", toDouble(b.getTotalMv()));
        putIfNotNull(bar, "circ_mv", toDouble(b.getCircMv()));
        putIfNotNull(bar, "turnover_rate", toDouble(b.getTurnoverRate()));
        putIfNotNull(bar, "turnover_rate_f", toDouble(b.getTurnoverRateF()));
        putIfNotNull(bar, "volume_ratio", toDouble(b.getVolumeRatio()));
    }

    /** 仅在 value 非 null 时写入 bar，避免回传大量 null 字段。 */
    private static void putIfNotNull(JSONObject bar, String key, Double value) {
        if (value != null) {
            bar.put(key, value);
        }
    }

    /**
     * 根据配置解析回测标的代码列表。
     * 指数成分股池（csi300/csi500）取回测区间内成分股并集，防幸存者偏差。
     * <p>
     * 注意：本方法对 csi300/csi500 使用 getConstituentsInRange 取回测区间成分股<b>并集</b>，
     * 用于一次性准备全量 K 线数据（保证回测期间任何调仓日都有数据）。
     * <p>
     * point-in-time 成分股过滤（消除 lookahead bias）由 engine 在每个调仓日通过
     * /api/internal/constituents/query 接口动态执行，不在本方法做。
     * 详见 spec 010-rotation-data-governance 缺陷 A 修复。
     * <p>
     * Phase 2（008-backtest-center-phase2）：universe=all_a_shares 全市场在 watcher 侧直接拒绝，
     * 避免 5000+ 标的 K 线撑爆 HTTP 载荷与 engine 回测超时；引导改用 csi300/csi500/manual 池。
     * <p>
     * spec 009-strategy-paradigm-exclusive：signals（择时）范式仅支持 manual 池且不超过
     * {@link StrategySchemaConstants#SIGNALS_MAX_UNIVERSE_SIZE} 只标的，watcher 侧二次校验。
     */
    private List<String> resolveBacktestSymbols(JSONObject configJson) {
        JSONObject screen = configJson.getJSONObject("screen_config");
        if (screen == null) {
            return extractSymbols(configJson);
        }
        // universe 兼容两种形态（spec 010 4 层结构迁移）：
        //   新结构：screen_config.universe 为对象 {pool, point_in_time, stocks}；
        //   旧扁平：screen_config.universe 为字符串（"csi300"/"manual"/...）。
        //   resolveUniversePool 统一归一为 pool 字符串。
        String universe = resolveUniversePool(screen);
        if (universe == null) {
            return extractSymbols(configJson);
        }
        if ("all_a_shares".equalsIgnoreCase(universe)) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_UNIVERSE_TOO_LARGE,
                    "全市场回测暂不支持，请用 csi300/csi500 或 manual 池");
        }
        // signals 范式 universe 二次校验：仅支持 manual（spec 009-strategy-paradigm-exclusive）
        if (hasSignals(configJson) && !"manual".equalsIgnoreCase(universe)) {
            throw new BusinessException(BacktestErrorCodes.SIGNALS_UNIVERSE_NOT_MANUAL,
                    "signals（择时）范式的选股范围仅支持 manual，当前为 " + universe);
        }
        if ("csi300".equalsIgnoreCase(universe) || "csi500".equalsIgnoreCase(universe)) {
            String indexCode = "csi300".equalsIgnoreCase(universe) ? "000300.SH" : "000905.SH";
            // 回测区间从 backtest_config.start_date/end_date（yyyyMMdd）取，留空表示全部历史
            JSONObject bt = configJson.getJSONObject("backtest_config");
            String startDate = bt == null ? null : normalizeDate(bt.getString("start_date"));
            String endDate = bt == null ? null : normalizeDate(bt.getString("end_date"));
            // 区间并集：仅用于数据准备，非 point-in-time（engine 侧按调仓日动态过滤）
            List<String> constituents = indexWeightService.getConstituentsInRange(indexCode, startDate, endDate);
            if (constituents.isEmpty()) {
                log.warn("回测成分股池[{}]在区间 {}~{} 内为空，请先初始化 index_weight", universe, startDate, endDate);
            }
            return constituents;
        }
        // manual 或其他：用 extractSymbols（从 screen_config.universe.stocks 提取）
        List<String> symbols = extractSymbols(configJson);
        // signals 范式 universe 规模上限校验（spec 009-strategy-paradigm-exclusive）
        if (hasSignals(configJson) && symbols.size() > StrategySchemaConstants.SIGNALS_MAX_UNIVERSE_SIZE) {
            throw new BusinessException(BacktestErrorCodes.SIGNALS_UNIVERSE_TOO_LARGE,
                    "signals（择时）范式的选股范围不得超过 " + StrategySchemaConstants.SIGNALS_MAX_UNIVERSE_SIZE
                            + " 只，当前 " + symbols.size() + " 只");
        }
        return symbols;
    }

    /**
     * 判断 trading_config 是否存在 signals 范式（与 {@code StrategyServiceImpl#deriveScope} 逻辑对齐）。
     */
    private boolean hasSignals(JSONObject configJson) {
        if (configJson == null) {
            return false;
        }
        JSONObject trading = configJson.getJSONObject("trading_config");
        return trading != null && trading.containsKey("signals");
    }

    /** 把 yyyy-MM-dd 或 yyyyMMdd 统一成 yyyyMMdd（tushare/index_weight 表格式） */
    private static String normalizeDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return date.replace("-", "");
    }

    /**
     * 从 config_json 的 screen_config.universe.stocks（universe.pool=manual 时指定的标的池）提取标的代码列表。
     * 兼容旧扁平结构（top-level screen_config.stocks）。回测标的由 watcher 根据 screen_config 构造 kline_data 决定，不再从 trading_config.symbols 读取。
     */
    private List<String> extractSymbols(JSONObject configJson) {
        List<String> out = new ArrayList<>();
        JSONObject screen = configJson.getJSONObject("screen_config");
        if (screen != null) {
            // 新 4 层：stocks 嵌在 universe 对象下；旧扁平：top-level stocks。两者皆取兜底。
            Object stocks = null;
            Object universe = screen.get("universe");
            if (universe instanceof JSONObject o) {
                stocks = o.get("stocks");
            }
            if (stocks == null) {
                stocks = screen.get("stocks");
            }
            addAllStrings(stocks, out);
        }
        // 去重保序
        return out.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 从 screen_config 解析 universe pool（兼容新 4 层对象结构与旧扁平字符串结构）。
     * <p>
     * 新结构（spec 010）：screen_config.universe 为对象 {pool, point_in_time, stocks}，取 .pool；
     * 旧扁平结构：screen_config.universe 为字符串（"csi300"/"csi500"/"manual"/"all_a_shares"/...），原样返回。
     *
     * @param screen screen_config 的 JSONObject，可为 null
     * @return pool 字符串；screen 为空或 universe 缺失/形态未知时返回 null
     */
    private static String resolveUniversePool(JSONObject screen) {
        if (screen == null) {
            return null;
        }
        Object universe = screen.get("universe");
        if (universe instanceof String s) {
            return s;
        }
        if (universe instanceof JSONObject o) {
            return o.getString("pool");
        }
        return null;
    }

    private static void addAllStrings(Object obj, List<String> out) {
        if (obj instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                Object e = arr.get(i);
                if (e instanceof String s && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        } else if (obj instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof String s && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
    }

    /**
     * 查 benchmark 的指数日线，组装 [{date, close}]。查空返回 null（降级，不阻断）。
     */
    private JSONObject buildBenchmarkData(String benchmark) {
        if (benchmark == null || benchmark.isBlank()) {
            return null;
        }
        List<DailyQuoteDO> quotes = dailyQuoteMapper.selectList(
                new LambdaQueryWrapper<DailyQuoteDO>()
                        .eq(DailyQuoteDO::getTsCode, benchmark)
                        .orderByAsc(DailyQuoteDO::getTradeDate));
        if (quotes.isEmpty()) {
            return null;
        }
        JSONObject obj = new JSONObject();
        List<String> dates = new ArrayList<>(quotes.size());
        List<Object> closes = new ArrayList<>(quotes.size());
        for (DailyQuoteDO q : quotes) {
            dates.add(q.getTradeDate());
            closes.add(toDouble(q.getClose()));
        }
        obj.put("dates", dates);
        obj.put("values", closes);
        obj.put("symbol", benchmark);
        return obj;
    }

    // ==================== 内部：报告持久化 ====================

    /**
     * 把 engine 返回的回测结果落 quant_backtest_report（一对一）。
     * 先按 backtest_id 删旧（重跑/重试场景），再 insert。
     */
    private void persistReport(Long backtestId, JSONObject result, JSONObject benchmarkData) {
        if (result == null) {
            log.warn("回测 {} engine 返回结果为空，跳过 report 持久化", backtestId);
            return;
        }
        // 删旧 report（如有）
        reportMapper.delete(new LambdaQueryWrapper<QuantBacktestReportDO>()
                .eq(QuantBacktestReportDO::getBacktestId, backtestId));

        QuantBacktestReportDO r = new QuantBacktestReportDO();
        r.setBacktestId(backtestId);
        r.setMetricsJson(jsonStr(result.get("metrics")));
        r.setEquityCurveJson(jsonStr(result.get("equity_curve")));
        // engine 不一定返回 benchmark_curve；watcher 侧用查到的 benchmark_data 兜底
        Object benchCurve = result.get("benchmark_curve");
        if (benchCurve == null && benchmarkData != null) {
            benchCurve = benchmarkData;
        }
        r.setBenchmarkCurveJson(jsonStr(benchCurve));
        r.setDailyReturnsJson(jsonStr(result.get("daily_returns")));
        r.setTradesJson(jsonStr(result.get("trades")));
        r.setOrdersJson(jsonStr(result.get("orders")));
        r.setPositionsJson(jsonStr(result.get("positions")));
        // spec 011 P0-5 / P2-5：调仓诊断 + 实际生效配置（warmup 等）
        r.setRebalanceDiagnosisJson(jsonStr(result.get("rebalance_diagnosis")));
        r.setEffectiveConfigJson(jsonStr(result.get("effective_config")));
        // spec 013 P2-9：执行诊断（分批调仓 + 冲击成本）
        r.setExecutionDiagnosisJson(jsonStr(result.get("execution_diagnosis")));
        r.setCreatedAt(Instant.now().toString());
        reportMapper.insert(r);
    }

    private static String jsonStr(Object obj) {
        return obj == null ? null : JSON.toJSONString(obj);
    }

    // ==================== 内部：校验与解析 ====================

    private QuantStrategyDO requireStrategyByUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_STRATEGY_VERSION_INVALID, "策略ID不能为空");
        }
        QuantStrategyDO strategy = strategyMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyDO>().eq(QuantStrategyDO::getUuid, uuid));
        if (strategy == null) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_STRATEGY_VERSION_INVALID, "策略不存在: " + uuid);
        }
        return strategy;
    }

    private QuantStrategyVersionDO loadVersion(Long strategyPkId, Integer versionNo) {
        QuantStrategyVersionDO v = versionMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyVersionDO>()
                        .eq(QuantStrategyVersionDO::getStrategyId, strategyPkId)
                        .eq(QuantStrategyVersionDO::getVersionNo, versionNo));
        if (v == null) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_STRATEGY_VERSION_INVALID,
                    "策略版本不存在: " + versionNo);
        }
        return v;
    }

    /**
     * 范式校验占位（Phase 2 放宽）。
     * <p>
     * 第一波曾在此拒绝 trading_config.rebalance 与 exit.rules；第二波 spec（008-backtest-center-phase2）
     * 已将这两类范式的处理下沉到 engine（compiler.py 编译期生成 on_daily_rebalance / on_bar exit 分支），
     * watcher 侧透传即可，故本方法不再拒绝上述范式。
     * <p>
     * GRID / WALK_FORWARD 模式拒绝属第三波，由 run() 入口对 req.mode 的校验独立负责，
     * 不在此方法内；本方法保留为 no-op，便于后续按需扩展（如组合层风控校验）。
     */
    private void checkParadigmSupported(JSONObject configJson) {
        // Phase 2：rebalance / exit.rules / use_atr_stop 不再在 watcher 侧拒绝。
    }

    /**
     * benchmark 解析：overrideConfig.benchmark > config.backtest_config.benchmark > 默认 000300.SH。
     */
    private String resolveBenchmark(BacktestRunRequestDTO req, JSONObject configJson) {
        if (req.getBenchmark() != null && !req.getBenchmark().isBlank()) {
            return req.getBenchmark().trim();
        }
        if (req.getOverrideConfig() != null) {
            Object oc = req.getOverrideConfig().get("benchmark");
            if (oc instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        if (configJson != null) {
            JSONObject bt = configJson.getJSONObject("backtest_config");
            if (bt != null) {
                Object bc = bt.get("benchmark");
                if (bc instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            }
        }
        return DEFAULT_BENCHMARK;
    }

    private JSONObject parseJsonSafely(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object parsed = JSON.parse(json);
            return parsed instanceof JSONObject jo ? jo : null;
        } catch (Exception e) {
            log.warn("config_json 解析失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 内部：查询辅助 ====================

    private QuantBacktestDO requireTaskByUuid(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_NOT_FOUND, "taskId 不能为空");
        }
        QuantBacktestDO task = backtestMapper.selectOne(
                new LambdaQueryWrapper<QuantBacktestDO>().eq(QuantBacktestDO::getTaskId, taskId));
        if (task == null) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_NOT_FOUND, "回测任务不存在: " + taskId);
        }
        return task;
    }

    // ==================== 内部：DTO 转换 ====================

    /**
     * 批量加载策略主表，用于列表页避免 N+1 查询。
     * 返回 {@code strategyId(PK) -> QuantStrategyDO} 的 Map。
     */
    private Map<Long, QuantStrategyDO> batchLoadStrategies(List<QuantBacktestDO> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = tasks.stream()
                .map(QuantBacktestDO::getStrategyId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<QuantStrategyDO> strategies = strategyMapper.selectBatchIds(ids);
        return strategies.stream().collect(Collectors.toMap(QuantStrategyDO::getId, s -> s, (a, b) -> a));
    }

    private BacktestTaskVO toTaskVO(QuantBacktestDO t) {
        // 单任务查询场景：直接 selectById 主表填充 uuid/name
        Map<Long, QuantStrategyDO> strategyMap = batchLoadStrategies(Collections.singletonList(t));
        return toTaskVO(t, strategyMap);
    }

    private BacktestTaskVO toTaskVO(QuantBacktestDO t, Map<Long, QuantStrategyDO> strategyMap) {
        BacktestTaskVO vo = new BacktestTaskVO();
        vo.setId(t.getId());
        vo.setTaskId(t.getTaskId());
        vo.setStrategyId(t.getStrategyId());
        // JOIN 主表填充 uuid 和 name（前端展示与跳转用）
        if (t.getStrategyId() != null && strategyMap != null) {
            QuantStrategyDO strategy = strategyMap.get(t.getStrategyId());
            if (strategy != null) {
                vo.setStrategyUuid(strategy.getUuid());
                vo.setStrategyName(strategy.getName());
            }
        }
        vo.setVersionNo(t.getVersionNo());
        vo.setMode(t.getMode());
        vo.setStatus(t.getStatus());
        vo.setProgress(t.getProgress());
        vo.setErrorMessage(t.getErrorMessage());
        vo.setBenchmark(t.getBenchmark());
        vo.setCreatedBy(t.getCreatedBy());
        vo.setStartedAt(t.getStartedAt());
        vo.setFinishedAt(t.getFinishedAt());
        vo.setCreatedAt(t.getCreatedAt());
        return vo;
    }

    private BacktestReportVO toReportVO(QuantBacktestReportDO r) {
        BacktestReportVO vo = new BacktestReportVO();
        vo.setMetrics(parseMap(r.getMetricsJson()));
        vo.setEquityCurve(parseMap(r.getEquityCurveJson()));
        vo.setBenchmarkCurve(parseMap(r.getBenchmarkCurveJson()));
        vo.setDailyReturns(parseList(r.getDailyReturnsJson()));
        vo.setTrades(parseList(r.getTradesJson()));
        vo.setOrders(parseList(r.getOrdersJson()));
        vo.setPositions(parseList(r.getPositionsJson()));
        // spec 011 P0-5 / P2-5：透传调仓诊断 + 实际生效配置
        vo.setRebalanceDiagnosis(parseMap(r.getRebalanceDiagnosisJson()));
        vo.setEffectiveConfig(parseMap(r.getEffectiveConfigJson()));
        // spec 013 P2-9：透传执行诊断（分批调仓 + 冲击成本）
        vo.setExecutionDiagnosis(parseMap(r.getExecutionDiagnosisJson()));
        return vo;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object o = JSON.parse(json);
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
                return out;
            }
        } catch (Exception e) {
            log.warn("report JSON 解析失败: {}", e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> parseList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object o = JSON.parse(json);
            if (o instanceof List<?> l) {
                return new ArrayList<>(l);
            }
        } catch (Exception e) {
            log.warn("report JSON 解析失败: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 内部：工具 ====================

    private static String truncate(String msg) {
        if (msg != null && msg.length() > MAX_ERROR_MSG_LEN) {
            return msg.substring(0, MAX_ERROR_MSG_LEN);
        }
        return msg;
    }

    private static Double toDouble(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double toDouble(BigDecimal bd) {
        return bd == null ? null : bd.doubleValue();
    }
}
