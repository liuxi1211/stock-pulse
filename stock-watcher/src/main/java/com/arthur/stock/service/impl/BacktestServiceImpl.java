package com.arthur.stock.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.client.BacktestClient;
import com.arthur.stock.constant.BacktestErrorCodes;
import com.arthur.stock.constant.BacktestModeEnum;
import com.arthur.stock.constant.BacktestStatusEnum;
import com.arthur.stock.constant.StrategyStatusEnum;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.backtest.BacktestCompareVO;
import com.arthur.stock.dto.backtest.BacktestReportVO;
import com.arthur.stock.dto.backtest.BacktestRunRequestDTO;
import com.arthur.stock.dto.backtest.BacktestTaskVO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.mapper.QuantBacktestMapper;
import com.arthur.stock.mapper.QuantBacktestReportMapper;
import com.arthur.stock.mapper.QuantStrategyMapper;
import com.arthur.stock.mapper.QuantStrategyVersionMapper;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.model.QuantBacktestDO;
import com.arthur.stock.model.QuantBacktestReportDO;
import com.arthur.stock.model.QuantStrategyDO;
import com.arthur.stock.model.QuantStrategyVersionDO;
import com.arthur.stock.service.BacktestService;
import com.arthur.stock.service.IndexWeightService;
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
    private final BacktestClient backtestClient;
    private final Executor backtestExecutor;
    private final IndexWeightService indexWeightService;

    public BacktestServiceImpl(QuantBacktestMapper backtestMapper,
                               QuantBacktestReportMapper reportMapper,
                               QuantStrategyMapper strategyMapper,
                               QuantStrategyVersionMapper versionMapper,
                               DailyQuoteMapper dailyQuoteMapper,
                               BacktestClient backtestClient,
                               @Qualifier("backtestExecutor") Executor backtestExecutor,
                               IndexWeightService indexWeightService) {
        this.backtestMapper = backtestMapper;
        this.reportMapper = reportMapper;
        this.strategyMapper = strategyMapper;
        this.versionMapper = versionMapper;
        this.dailyQuoteMapper = dailyQuoteMapper;
        this.backtestClient = backtestClient;
        this.backtestExecutor = backtestExecutor;
        this.indexWeightService = indexWeightService;
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

        // 2. 查策略
        QuantStrategyDO strategy = requireStrategy(req.getStrategyId());

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
        task.setStrategyId(strategy.getStrategyId());
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
        backtestExecutor.execute(() -> executeAsync(taskId, taskUuid, finalConfig, finalBenchmark, finalOverrideConfig));

        return toTaskVO(task);
    }

    /**
     * 异步执行回测：PENDING→RUNNING → 拼 kline/benchmark → 调 engine → 落 report + SUCCESS。
     * 任何异常都落 FAILED + error_message 截断 1024。
     */
    private void executeAsync(Long taskId, String taskUuid, JSONObject configJson,
                              String benchmark, String overrideConfigStr) {
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
            engineReq.put("strategyId", configJson == null ? null : configJson.get("strategyId"));
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
    public PageResult<BacktestTaskVO> listTasks(int page, int size, String strategyId, String status,
                                                 String startDate, String endDate) {
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : size;

        LambdaQueryWrapper<QuantBacktestDO> qw = new LambdaQueryWrapper<>();
        if (strategyId != null && !strategyId.isBlank()) {
            qw.eq(QuantBacktestDO::getStrategyId, strategyId);
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
        List<BacktestTaskVO> list = pg.getRecords().stream().map(this::toTaskVO).toList();
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
        BacktestRunRequestDTO req = new BacktestRunRequestDTO();
        req.setMode(origin.getMode());
        req.setStrategyId(origin.getStrategyId());
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
     *   <li>screen_config.universe=manual：用 screen_config.stocks；</li>
     *   <li>screen_config.universe=csi300/csi500：取回测区间内所有曾入选该指数的成分股并集（防幸存者偏差）；</li>
     *   <li>其他（all_a_shares 等）：全市场（暂不回测，返回 null 由调用方报错）。</li>
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
        JSONObject kline = new JSONObject();
        for (String code : symbols) {
            List<DailyQuoteDO> quotes = dailyQuoteMapper.selectList(
                    new LambdaQueryWrapper<DailyQuoteDO>()
                            .eq(DailyQuoteDO::getTsCode, code)
                            .orderByAsc(DailyQuoteDO::getTradeDate));
            if (quotes.isEmpty()) {
                continue;
            }
            JSONArray arr = new JSONArray(quotes.size());
            for (DailyQuoteDO q : quotes) {
                JSONObject bar = new JSONObject();
                bar.put("date", q.getTradeDate());
                bar.put("open", toDouble(q.getOpen()));
                bar.put("high", toDouble(q.getHigh()));
                bar.put("low", toDouble(q.getLow()));
                bar.put("close", toDouble(q.getClose()));
                bar.put("volume", toDouble(q.getVol()));
                arr.add(bar);
            }
            kline.put(code, arr);
        }
        return kline.isEmpty() ? null : kline;
    }

    /**
     * 根据配置解析回测标的代码列表。
     * 指数成分股池（csi300/csi500）取回测区间内成分股并集，防幸存者偏差。
     */
    private List<String> resolveBacktestSymbols(JSONObject configJson) {
        JSONObject screen = configJson.getJSONObject("screen_config");
        if (screen == null) {
            return extractSymbols(configJson);
        }
        String universe = screen.getString("universe");
        if (universe == null) {
            return extractSymbols(configJson);
        }
        if ("csi300".equalsIgnoreCase(universe) || "csi500".equalsIgnoreCase(universe)) {
            String indexCode = "csi300".equalsIgnoreCase(universe) ? "000300.SH" : "000905.SH";
            // 回测区间从 backtest_config.start_date/end_date（yyyyMMdd）取，留空表示全部历史
            JSONObject bt = configJson.getJSONObject("backtest_config");
            String startDate = bt == null ? null : normalizeDate(bt.getString("start_date"));
            String endDate = bt == null ? null : normalizeDate(bt.getString("end_date"));
            List<String> constituents = indexWeightService.getConstituentsInRange(indexCode, startDate, endDate);
            if (constituents.isEmpty()) {
                log.warn("回测成分股池[{}]在区间 {}~{} 内为空，请先初始化 index_weight", universe, startDate, endDate);
            }
            return constituents;
        }
        // manual 或其他：用 extractSymbols（从 screen_config.stocks 提取）
        return extractSymbols(configJson);
    }

    /** 把 yyyy-MM-dd 或 yyyyMMdd 统一成 yyyyMMdd（tushare/index_weight 表格式） */
    private static String normalizeDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return date.replace("-", "");
    }

    /**
     * 从 config_json 的 screen_config.stocks（universe=manual 时指定的标的池）提取标的代码列表。
     * 回测标的由 watcher 根据 screen_config 构造 kline_data 决定，不再从 trading_config.symbols 读取。
     */
    private List<String> extractSymbols(JSONObject configJson) {
        List<String> out = new ArrayList<>();
        JSONObject screen = configJson.getJSONObject("screen_config");
        if (screen != null) {
            addAllStrings(screen.get("stocks"), out);
        }
        // 去重保序
        return out.stream().distinct().collect(Collectors.toList());
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
        r.setCreatedAt(Instant.now().toString());
        reportMapper.insert(r);
    }

    private static String jsonStr(Object obj) {
        return obj == null ? null : JSON.toJSONString(obj);
    }

    // ==================== 内部：校验与解析 ====================

    private QuantStrategyDO requireStrategy(String strategyId) {
        if (strategyId == null || strategyId.isBlank()) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_STRATEGY_VERSION_INVALID, "策略ID不能为空");
        }
        QuantStrategyDO strategy = strategyMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyDO>().eq(QuantStrategyDO::getStrategyId, strategyId));
        if (strategy == null) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_STRATEGY_VERSION_INVALID, "策略不存在: " + strategyId);
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
     * 范式校验：config_json 含 trading_config.rebalance 或 exit.rules → 第一波不支持。
     */
    private void checkParadigmSupported(JSONObject configJson) {
        if (configJson == null) {
            return;
        }
        JSONObject trading = configJson.getJSONObject("trading_config");
        if (trading != null && trading.containsKey("rebalance")) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1,
                    "第一波不支持调仓型策略（trading_config.rebalance）");
        }
        JSONObject exit = configJson.getJSONObject("exit");
        if (exit != null && exit.containsKey("rules")) {
            throw new BusinessException(BacktestErrorCodes.BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1,
                    "第一波不支持含 exit.rules 的策略");
        }
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

    private BacktestTaskVO toTaskVO(QuantBacktestDO t) {
        BacktestTaskVO vo = new BacktestTaskVO();
        vo.setId(t.getId());
        vo.setTaskId(t.getTaskId());
        vo.setStrategyId(t.getStrategyId());
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
