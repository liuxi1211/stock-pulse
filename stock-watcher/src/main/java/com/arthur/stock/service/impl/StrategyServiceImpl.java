package com.arthur.stock.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.client.StrategyEngineClient;
import com.arthur.stock.constant.PositionSizingMethodEnum;
import com.arthur.stock.constant.StrategyErrorCodes;
import com.arthur.stock.constant.StrategyStatusEnum;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.strategy.StrategyConfigUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyCreateRequest;
import com.arthur.stock.dto.strategy.StrategyDTO;
import com.arthur.stock.dto.strategy.StrategyDiffDTO;
import com.arthur.stock.dto.strategy.StrategyPageRequest;
import com.arthur.stock.dto.strategy.StrategyRollbackRequest;
import com.arthur.stock.dto.strategy.StrategyStatusUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyValidationError;
import com.arthur.stock.dto.strategy.StrategyVersionDTO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.StrategyValidationException;
import com.arthur.stock.exception.StrategyVersionConflictException;
import com.arthur.stock.mapper.QuantStrategyMapper;
import com.arthur.stock.mapper.QuantStrategyVersionMapper;
import com.arthur.stock.mapper.QuantBacktestMapper;
import com.arthur.stock.mapper.QuantBacktestReportMapper;
import com.arthur.stock.model.QuantStrategyDO;
import com.arthur.stock.model.QuantStrategyVersionDO;
import com.arthur.stock.model.QuantBacktestDO;
import com.arthur.stock.model.QuantBacktestReportDO;
import com.arthur.stock.service.StrategyService;
import com.arthur.stock.util.JsonDiffUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 策略管理服务实现（spec 004 Task 6）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>事务边界</b>：engine {@code validate} HTTP 调用必须在事务<b>外</b>，避免长事务 + HTTP 失败脏写。
 *       本类用 {@link TransactionTemplate}（编程式事务）把「写主表 + 写版本 + 状态机」收敛到一个小事务里，
 *       避免 {@code @Transactional} 同类 self-invocation 导致代理失效。TransactionTemplate 由注入的
 *       {@link PlatformTransactionManager}（Spring Boot 自动配置）构造，无需额外 Bean 声明。</li>
 *   <li><b>乐观锁</b>：{@code expectedVersion != current_version} 抛
 *       {@link StrategyVersionConflictException}。</li>
 *   <li><b>状态机</b>：DRAFT 在配置校验通过后自动 -> VERIFIED；显式状态变更走
 *       {@link StrategyStatusEnum#canTransitionTo} 校验。</li>
 *   <li><b>时间字段</b>：统一 {@link Instant#now()} + {@link Instant#toString()}（UTC ISO8601）。</li>
 *   <li><b>tags</b>：入库前 trim + 剔除含逗号 tag + join(",")。</li>
 * </ul>
 */
@Slf4j
@Service
public class StrategyServiceImpl implements StrategyService {

    /** 配置 JSON 大小上限：1MB（与 Controller @Size 一致，Service 再兜底）。 */
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;

    private final QuantStrategyMapper strategyMapper;
    private final QuantStrategyVersionMapper versionMapper;
    private final StrategyEngineClient engineClient;
    private final TransactionTemplate transactionTemplate;
    private final QuantBacktestMapper backtestMapper;
    private final QuantBacktestReportMapper backtestReportMapper;

    public StrategyServiceImpl(QuantStrategyMapper strategyMapper,
                               QuantStrategyVersionMapper versionMapper,
                               StrategyEngineClient engineClient,
                               PlatformTransactionManager transactionManager,
                               QuantBacktestMapper backtestMapper,
                               QuantBacktestReportMapper backtestReportMapper) {
        this.strategyMapper = strategyMapper;
        this.versionMapper = versionMapper;
        this.engineClient = engineClient;
        // 由 Spring Boot 自动配置的 PlatformTransactionManager 构造，无需额外 Bean
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.backtestMapper = backtestMapper;
        this.backtestReportMapper = backtestReportMapper;
    }

    // ==================== createStrategy ====================

    @Override
    public StrategyDTO createStrategy(StrategyCreateRequest req) {
        // 1. 主表基础字段（无事务）
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String now = Instant.now().toString();
        String tags = normalizeTags(req.getTags());

        boolean hasConfig = req.getConfigJson() != null && !req.getConfigJson().isBlank();
        String configJson;
        String initialStatus;

        if (hasConfig) {
            // 2. 长度 + JSON 校验
            checkConfigSize(req.getConfigJson());
            parseConfigJson(req.getConfigJson());

            // 3. engine 校验（事务外）
            List<StrategyValidationError> errors = engineClient.validate(req.getConfigJson());
            if (!errors.isEmpty()) {
                throw new StrategyValidationException(errors);
            }
            configJson = req.getConfigJson();
            initialStatus = StrategyStatusEnum.VERIFIED.getCode();
        } else {
            configJson = buildDefaultConfig(req.getName());
            initialStatus = StrategyStatusEnum.DRAFT.getCode();
        }

        // 4. 事务内落库：主表 + v1
        QuantStrategyDO strategy = transactionTemplate.execute(status -> {
            QuantStrategyDO s = new QuantStrategyDO();
            s.setUuid(uuid);
            s.setName(req.getName());
            s.setDescription(req.getDescription());
            s.setCategory(req.getCategory());
            s.setScope(deriveScope(configJson));
            s.setStatus(initialStatus);
            s.setTags(tags);
            s.setCurrentVersion(1);
            s.setCreatedAt(now);
            s.setUpdatedAt(now);
            strategyMapper.insert(s);

            QuantStrategyVersionDO v1 = new QuantStrategyVersionDO();
            v1.setStrategyId(s.getId());
            v1.setVersionNo(1);
            v1.setConfigJson(configJson);
            v1.setChangelog(req.getChangelog());
            v1.setCreatedAt(now);
            versionMapper.insert(v1);
            return s;
        });

        // 5. 返回详情（含 config）
        return toDetailDTO(requireStrategyByUuid(uuid), configJson);
    }

    // ==================== getStrategiesPage ====================

    @Override
    public PageResult<StrategyDTO> getStrategiesPage(StrategyPageRequest req) {
        int page = req.getPage() == null || req.getPage() < 1 ? 1 : req.getPage();
        int size = req.getSize() == null || req.getSize() < 1 ? 20 : req.getSize();

        LambdaQueryWrapper<QuantStrategyDO> qw = new LambdaQueryWrapper<>();
        if (req.getKeyword() != null && !req.getKeyword().isBlank()) {
            String kw = req.getKeyword().trim();
            qw.and(w -> w.like(QuantStrategyDO::getName, kw)
                    .or().like(QuantStrategyDO::getDescription, kw));
        }
        if (req.getCategory() != null && !req.getCategory().isBlank()) {
            qw.eq(QuantStrategyDO::getCategory, req.getCategory());
        }
        if (req.getScope() != null && !req.getScope().isBlank()) {
            qw.eq(QuantStrategyDO::getScope, req.getScope());
        }
        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            qw.eq(QuantStrategyDO::getStatus, req.getStatus());
        } else {
            // 默认排除已归档
            qw.ne(QuantStrategyDO::getStatus, StrategyStatusEnum.ARCHIVED.getCode());
        }
        if (req.getTag() != null && !req.getTag().isBlank()) {
            // tags 逗号分隔存储，用 FIND_IN_SET 精确匹配
            qw.apply("FIND_IN_SET({0}, tags)", req.getTag());
        }
        qw.orderByDesc(QuantStrategyDO::getUpdatedAt);

        Page<QuantStrategyDO> p = strategyMapper.selectPage(new Page<>(page, size), qw);
        List<StrategyDTO> list = p.getRecords().stream()
                .map(this::toListDTO)
                .toList();
        return PageResult.of(list, p.getTotal(), page, size);
    }

    // ==================== getStrategyDetail ====================

    @Override
    public StrategyDTO getStrategyDetail(String uuid) {
        QuantStrategyDO strategy = requireStrategyByUuid(uuid);
        String config = loadVersionConfig(strategy.getId(), strategy.getCurrentVersion());
        return toDetailDTO(strategy, config);
    }

    // ==================== updateStrategy（元信息） ====================

    @Override
    public void updateStrategy(String uuid, StrategyUpdateRequest req) {
        QuantStrategyDO strategy = requireStrategyByUuid(uuid);
        if (req.getName() != null) {
            strategy.setName(req.getName());
        }
        if (req.getDescription() != null) {
            strategy.setDescription(req.getDescription());
        }
        if (req.getCategory() != null) {
            strategy.setCategory(req.getCategory());
        }
        if (req.getTags() != null) {
            strategy.setTags(normalizeTags(req.getTags()));
        }
        strategy.setUpdatedAt(Instant.now().toString());
        strategyMapper.updateById(strategy);
    }

    // ==================== updateStrategyConfig（核心：新版本） ====================

    @Override
    public StrategyDTO updateStrategyConfig(String uuid, StrategyConfigUpdateRequest req) {
        // 1. 长度校验（兜底）
        checkConfigSize(req.getConfigJson());
        // 2. JSON 解析校验（兜底）
        parseConfigJson(req.getConfigJson());

        // 3. 查主表 + 乐观锁
        QuantStrategyDO strategy = requireStrategyByUuid(uuid);
        Integer currentVersion = strategy.getCurrentVersion();
        if (req.getExpectedVersion() != null && !req.getExpectedVersion().equals(currentVersion)) {
            throw new StrategyVersionConflictException(currentVersion);
        }

        // 4. engine 校验（事务外）
        List<StrategyValidationError> errors = engineClient.validate(req.getConfigJson());
        if (!errors.isEmpty()) {
            throw new StrategyValidationException(errors);
        }

        // 5. 事务内：写新版本 + 更主表 current_version + 状态机
        int newVersionNo = (currentVersion == null ? 0 : currentVersion) + 1;
        final int finalNewVersionNo = newVersionNo;
        final String finalConfigJson = req.getConfigJson();
        final String finalChangelog = req.getChangelog();
        final String now = Instant.now().toString();

        transactionTemplate.executeWithoutResult(status -> {
            // 状态机自动转换：DRAFT -> VERIFIED；ACTIVE/VERIFIED 保持
            String nextStatus = strategy.getStatus();
            if (StrategyStatusEnum.DRAFT.getCode().equals(strategy.getStatus())) {
                nextStatus = StrategyStatusEnum.VERIFIED.getCode();
            }

            QuantStrategyVersionDO v = new QuantStrategyVersionDO();
            v.setStrategyId(strategy.getId());
            v.setVersionNo(finalNewVersionNo);
            v.setConfigJson(finalConfigJson);
            v.setChangelog(finalChangelog);
            v.setCreatedAt(now);
            versionMapper.insert(v);

            strategy.setCurrentVersion(finalNewVersionNo);
            strategy.setStatus(nextStatus);
            strategy.setScope(deriveScope(finalConfigJson));
            strategy.setUpdatedAt(now);
            strategyMapper.updateById(strategy);
        });

        // 6. 返回详情
        return toDetailDTO(requireStrategyByUuid(uuid), req.getConfigJson());
    }

    // ==================== deleteStrategy（软删） ====================

    @Override
    public void deleteStrategy(String uuid) {
        QuantStrategyDO strategy = requireStrategyByUuid(uuid);
        strategy.setStatus(StrategyStatusEnum.ARCHIVED.getCode());
        strategy.setUpdatedAt(Instant.now().toString());
        strategyMapper.updateById(strategy);
    }

    // ==================== updateStatus（状态机） ====================

    @Override
    public void updateStatus(String uuid, StrategyStatusUpdateRequest req) {
        QuantStrategyDO strategy = requireStrategyByUuid(uuid);
        StrategyStatusEnum current = StrategyStatusEnum.fromCode(strategy.getStatus());
        StrategyStatusEnum target = StrategyStatusEnum.fromCode(req.getStatus());
        if (target == null) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_INVALID_STATUS_TRANSITION,
                    "未知目标状态: " + req.getStatus());
        }
        if (current == null || !current.canTransitionTo(target)) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_INVALID_STATUS_TRANSITION,
                    "状态 " + (current == null ? strategy.getStatus() : current.getCode())
                            + " 不允许流转到 " + target.getCode());
        }
        strategy.setStatus(target.getCode());
        strategy.setUpdatedAt(Instant.now().toString());
        strategyMapper.updateById(strategy);
    }

    // ==================== 版本相关 ====================

    @Override
    public List<StrategyVersionDTO> listVersions(String uuid) {
        QuantStrategyDO strategy = requireStrategyByUuid(uuid);
        List<QuantStrategyVersionDO> versions = versionMapper.selectList(
                new LambdaQueryWrapper<QuantStrategyVersionDO>()
                        .eq(QuantStrategyVersionDO::getStrategyId, strategy.getId())
                        .orderByDesc(QuantStrategyVersionDO::getVersionNo));
        final String status = strategy.getStatus();
        return versions.stream().map(v -> toListVersionDTO(v, status)).toList();
    }

    @Override
    public StrategyVersionDTO getVersion(String uuid, Integer versionNo) {
        QuantStrategyDO strategy = requireStrategyByUuid(uuid);
        QuantStrategyVersionDO v = versionMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyVersionDO>()
                        .eq(QuantStrategyVersionDO::getStrategyId, strategy.getId())
                        .eq(QuantStrategyVersionDO::getVersionNo, versionNo));
        if (v == null) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_VERSION_NOT_FOUND,
                    "版本不存在: " + versionNo);
        }
        return toDetailVersionDTO(v, strategy.getStatus());
    }

    @Override
    public List<StrategyDiffDTO> diffVersions(String uuid, Integer fromVer, Integer toVer) {
        QuantStrategyDO strategy = requireStrategyByUuid(uuid);
        String fromJson = loadVersionConfig(strategy.getId(), fromVer);
        String toJson = loadVersionConfig(strategy.getId(), toVer);
        return JsonDiffUtil.diff(fromJson, toJson);
    }

    @Override
    public StrategyVersionDTO rollbackVersion(String uuid, StrategyRollbackRequest req) {
        QuantStrategyDO strategy = requireStrategyByUuid(uuid);
        if (req.getTargetVersion() == null) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_VERSION_NOT_FOUND, "目标版本号不能为空");
        }
        QuantStrategyVersionDO target = versionMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyVersionDO>()
                        .eq(QuantStrategyVersionDO::getStrategyId, strategy.getId())
                        .eq(QuantStrategyVersionDO::getVersionNo, req.getTargetVersion()));
        if (target == null) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_VERSION_NOT_FOUND,
                    "回滚目标版本不存在: " + req.getTargetVersion());
        }

        // 复用 updateStrategyConfig：以目标版本 config 写新版本，重走校验/乐观锁/状态机
        String prefix = "回滚到 v" + req.getTargetVersion();
        String changelog = req.getChangelog() == null || req.getChangelog().isBlank()
                ? prefix : prefix + "：" + req.getChangelog();

        StrategyConfigUpdateRequest configReq = new StrategyConfigUpdateRequest();
        configReq.setConfigJson(target.getConfigJson());
        configReq.setExpectedVersion(strategy.getCurrentVersion());
        configReq.setChangelog(changelog);
        updateStrategyConfig(uuid, configReq);

        // 返回最新版本（current_version 已更新）
        return getVersion(uuid, requireStrategyByUuid(uuid).getCurrentVersion());
    }

    // ==================== 统计与版本对比 ====================

    @Override
    public com.arthur.stock.dto.strategy.StrategyStatsDTO getStats() {
        com.arthur.stock.dto.strategy.StrategyStatsDTO stats = new com.arthur.stock.dto.strategy.StrategyStatsDTO();
        // 直接用 BaseMapper.selectCount 按状态精确计数（含 ARCHIVED）
        stats.setVerified(countByStatus(StrategyStatusEnum.VERIFIED.getCode()));
        stats.setActive(countByStatus(StrategyStatusEnum.ACTIVE.getCode()));
        stats.setDraft(countByStatus(StrategyStatusEnum.DRAFT.getCode()));
        stats.setArchived(countByStatus(StrategyStatusEnum.ARCHIVED.getCode()));
        // total = 不含 ARCHIVED 的总和（与列表默认排除 ARCHIVED 行为一致）
        stats.setTotal(stats.getVerified() + stats.getActive() + stats.getDraft());
        return stats;
    }

    /**
     * 按状态计数（单次 count 查询，比构造分页对象更高效）。
     */
    private long countByStatus(String status) {
        try {
            Long c = strategyMapper.selectCount(
                    new LambdaQueryWrapper<QuantStrategyDO>().eq(QuantStrategyDO::getStatus, status));
            return c == null ? 0L : c;
        } catch (Exception e) {
            log.warn("按状态计数失败 status={}: {}", status, e.getMessage());
            return 0L;
        }
    }

    @Override
    public com.arthur.stock.dto.strategy.StrategyVersionCompareDTO compareVersions(String uuid, Integer fromVer, Integer toVer) {
        // 联查 quant_backtest_report 返回真实回测指标（spec 007 T5.1）。
        // 对 fromVer/toVer 各取最近一条 SUCCESS 回测的 metrics_json，提取 5 项核心指标。
        com.arthur.stock.dto.strategy.StrategyVersionCompareDTO result =
                new com.arthur.stock.dto.strategy.StrategyVersionCompareDTO();

        // uuid -> 主表 PK id，再查 quant_backtest（strategy_id 现在是 BIGINT 主键）
        Long strategyPkId = requireStrategyByUuid(uuid).getId();
        Map<String, Double> fromMetrics = loadLatestSuccessMetrics(strategyPkId, fromVer);
        Map<String, Double> toMetrics = loadLatestSuccessMetrics(strategyPkId, toVer);

        // 5 项核心指标对比
        String[][] metricDefs = {
                {"total_return_pct", "总收益(%)"},
                {"sharpe_ratio", "夏普比率"},
                {"max_drawdown_pct", "最大回撤(%)"},
                {"win_rate", "胜率(%)"},
                {"trade_count", "交易笔数"}
        };
        for (String[] def : metricDefs) {
            String key = def[0];
            String labelCn = def[1];
            com.arthur.stock.dto.strategy.StrategyVersionCompareDTO.MetricRow row =
                    new com.arthur.stock.dto.strategy.StrategyVersionCompareDTO.MetricRow();
            row.setLabel(key);
            row.setLabelCn(labelCn);
            row.setFromVal(fromMetrics.get(key));
            row.setToVal(toMetrics.get(key));
            if (row.getFromVal() != null && row.getToVal() != null) {
                row.setDelta(row.getToVal() - row.getFromVal());
            }
            result.getMetrics().add(row);
        }
        return result;
    }

    /**
     * 查指定策略版本的最近一条 SUCCESS 回测的 metrics。
     * 按 created_at DESC 取首条，反序列化 metrics_json 提取 5 项核心指标。
     * 无回测记录返回空 Map（前端展示"暂无回测数据"）。
     *
     * @param strategyPkId 策略主表 PK（quant_strategy.id）
     */
    private Map<String, Double> loadLatestSuccessMetrics(Long strategyPkId, Integer versionNo) {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            QuantBacktestDO bt = backtestMapper.selectOne(
                    new LambdaQueryWrapper<QuantBacktestDO>()
                            .eq(QuantBacktestDO::getStrategyId, strategyPkId)
                            .eq(QuantBacktestDO::getVersionNo, versionNo)
                            .eq(QuantBacktestDO::getStatus, "SUCCESS")
                            .orderByDesc(QuantBacktestDO::getCreatedAt)
                            .last("LIMIT 1"));
            if (bt == null) {
                return out;
            }
            QuantBacktestReportDO report = backtestReportMapper.selectOne(
                    new LambdaQueryWrapper<QuantBacktestReportDO>()
                            .eq(QuantBacktestReportDO::getBacktestId, bt.getId())
                            .last("LIMIT 1"));
            if (report == null || report.getMetricsJson() == null) {
                return out;
            }
            JSONObject metrics = JSON.parseObject(report.getMetricsJson());
            for (String key : new String[]{"total_return_pct", "sharpe_ratio", "max_drawdown_pct", "win_rate", "trade_count"}) {
                Double v = metrics.getDouble(key);
                if (v != null) {
                    out.put(key, v);
                }
            }
        } catch (Exception e) {
            log.warn("加载版本回测指标失败 strategyPkId={} versionNo={}: {}", strategyPkId, versionNo, e.getMessage());
        }
        return out;
    }

    // ==================== 内部：加载与校验 ====================

    private QuantStrategyDO requireStrategyByUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_NOT_FOUND, "策略ID不能为空");
        }
        QuantStrategyDO strategy = strategyMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyDO>().eq(QuantStrategyDO::getUuid, uuid));
        if (strategy == null) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_NOT_FOUND, "策略不存在: " + uuid);
        }
        return strategy;
    }

    private String loadVersionConfig(Long strategyPkId, Integer versionNo) {
        if (versionNo == null) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_VERSION_NOT_FOUND, "版本号不能为空");
        }
        QuantStrategyVersionDO v = versionMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyVersionDO>()
                        .eq(QuantStrategyVersionDO::getStrategyId, strategyPkId)
                        .eq(QuantStrategyVersionDO::getVersionNo, versionNo));
        if (v == null) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_VERSION_NOT_FOUND,
                    "版本不存在: " + versionNo);
        }
        return sanitizeConfigJson(v.getConfigJson());
    }

    private void checkConfigSize(String configJson) {
        if (configJson == null) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_CONFIG_TOO_LARGE, "配置 JSON 不能为空");
        }
        if (configJson.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_CONFIG_TOO_LARGE,
                    "配置 JSON 超过 1MB 限制");
        }
    }

    private JSONObject parseConfigJson(String configJson) {
        try {
            Object parsed = JSON.parse(configJson);
            if (parsed instanceof JSONObject jo) {
                return jo;
            }
            throw new BusinessException(StrategyErrorCodes.STRATEGY_VALIDATION_FAILED,
                    "策略配置必须是 JSON 对象");
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(StrategyErrorCodes.STRATEGY_VALIDATION_FAILED,
                    "策略配置 JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 清洗存量配置 JSON：移除已废弃的顶层 scope 与 trading_config.symbols 字段，
     * 避免旧数据在 engine 侧（Pydantic extra="forbid"）校验失败或前端回显多余字段。
     */
    private String sanitizeConfigJson(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return configJson;
        }
        try {
            JSONObject root = JSON.parseObject(configJson);
            root.remove("scope");
            JSONObject trading = root.getJSONObject("trading_config");
            if (trading != null) {
                trading.remove("symbols");
            }
            return JSON.toJSONString(root);
        } catch (Exception e) {
            return configJson;
        }
    }

    /**
     * 标签归一化：trim 每项、剔除含逗号或空 tag、join(",")。
     */
    private String normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        String joined = tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .filter(t -> !t.contains(","))
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    // ==================== 内部：默认配置 ====================

    /**
     * 根据配置 JSON 的 trading_config 结构派生 scope（不再由用户编辑）。
     * <p>
     * signals 与 rebalance 范式互斥（spec 009-strategy-paradigm-exclusive），
     * 互斥约束由 engine validator 保证；此处防御性判断，signals 优先：
     * 有 signals -> single；有 rebalance -> portfolio；都没有 -> single。
     */
    private String deriveScope(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return "single";
        }
        try {
            JSONObject root = JSON.parseObject(configJson);
            JSONObject trading = root.getJSONObject("trading_config");
            if (trading == null) {
                return "single";
            }
            boolean hasSignals = trading.containsKey("signals");
            boolean hasRebalance = trading.containsKey("rebalance");
            if (hasSignals) {
                return "single";
            }
            if (hasRebalance) {
                return "portfolio";
            }
            return "single";
        } catch (Exception e) {
            return "single";
        }
    }

    /**
     * 构建最小默认配置（spec Task 6 Notes 示例）。
     */
    private String buildDefaultConfig(String name) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", name == null ? "" : name);
        root.put("description", "");

        Map<String, Object> trading = new LinkedHashMap<>();

        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("buy", signalBlock("AND"));
        signals.put("sell", signalBlock("AND"));
        trading.put("signals", signals);

        Map<String, Object> sizing = new LinkedHashMap<>();
        sizing.put("method", PositionSizingMethodEnum.ORDER_TARGET_PERCENT.getCode());
        sizing.put("target", 0.95);
        trading.put("position_sizing", sizing);
        root.put("trading_config", trading);

        Map<String, Object> backtest = new LinkedHashMap<>();
        backtest.put("initial_cash", 100000);
        backtest.put("broker_profile", "cn_stock_miniqmt");
        backtest.put("t_plus_one", true);
        backtest.put("lot_size", 100);
        backtest.put("warmup_period", 20);
        backtest.put("history_depth", 60);
        root.put("backtest_config", backtest);

        return JSON.toJSONString(root);
    }

    private static Map<String, Object> signalBlock(String operator) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operator", operator);
        m.put("conditions", Collections.emptyList());
        return m;
    }

    // ==================== 内部：DTO 转换 ====================

    private StrategyDTO toDetailDTO(QuantStrategyDO strategy, String config) {
        StrategyDTO dto = toListDTO(strategy);
        dto.setConfig(sanitizeConfigJson(config));
        return dto;
    }

    private StrategyDTO toListDTO(QuantStrategyDO strategy) {
        StrategyDTO dto = new StrategyDTO();
        dto.setId(strategy.getId());
        dto.setUuid(strategy.getUuid());
        dto.setName(strategy.getName());
        dto.setDescription(strategy.getDescription());
        dto.setCategory(strategy.getCategory());
        dto.setScope(strategy.getScope());
        dto.setStatus(strategy.getStatus());
        dto.setTags(parseTags(strategy.getTags()));
        dto.setCurrentVersion(strategy.getCurrentVersion());
        dto.setCreatedAt(strategy.getCreatedAt());
        dto.setUpdatedAt(strategy.getUpdatedAt());
        return dto;
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(Arrays.asList(tags.split(",")));
    }

    private StrategyVersionDTO toListVersionDTO(QuantStrategyVersionDO v, String status) {
        StrategyVersionDTO dto = new StrategyVersionDTO();
        dto.setVersionNo(v.getVersionNo());
        dto.setStatus(status);
        dto.setChangelog(v.getChangelog());
        dto.setCreatedAt(v.getCreatedAt());
        return dto;
    }

    private StrategyVersionDTO toDetailVersionDTO(QuantStrategyVersionDO v, String status) {
        StrategyVersionDTO dto = toListVersionDTO(v, status);
        dto.setConfigJson(sanitizeConfigJson(v.getConfigJson()));
        return dto;
    }
}
