package com.arthur.stock.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.client.StrategyEngineClient;
import com.arthur.stock.dto.strategy.StrategyTemplateDTO;
import com.arthur.stock.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略模板加载器（spec 004 Task 7，FR-1 / FR-13 / NFR-3）。
 * <p>
 * 启动时扫描 {@code classpath:strategies/templates/*.json}，解析为 {@link StrategyTemplateDTO}
 * 并缓存到 {@link ConcurrentHashMap}。
 * <p>
 * <b>模板 JSON 拆分</b>：模板顶层含 watcher 元数据（{@code category/tags} 等不在统一 Schema 内）和
 * config 子树（{@code strategy_id/name/description/scope/screen_config/trading_config/backtest_config}）。
 * 加载时按 spec 004 §3.3 的字段映射分别提取：
 * <ul>
 *   <li>{@link StrategyTemplateDTO#name} / {@code description}：优先用顶层（模板展示名/描述），
 *       缺失时回落到 config 子树内同名字段。</li>
 *   <li>{@code scope} / {@code category}：仅顶层有，直接取。</li>
 *   <li>{@code tags}：仅顶层有，仅作展示，不写入 configJson。</li>
 *   <li>{@link StrategyTemplateDTO#getConfigJson()}：config 子树（剔除 {@code category/tags}）
 *       重新序列化为 JSON 字符串，保证写入 {@code quant_strategy_version.config_json} 的内容
 *       严格符合统一策略 Schema。</li>
 * </ul>
 * <p>
 * <b>启动校验策略</b>（鲁棒优先，spec FR-13）：
 * <ul>
 *   <li>仅 {@code dev} profile 下尝试调 {@link StrategyEngineClient#validate}；</li>
 *   <li>校验失败 <b>不剔除模板</b>，仅打 WARN 日志（含模板 id 与首个错误）；</li>
 *   <li>engine 不可达时跳过校验，正常缓存。</li>
 * </ul>
 * 原因：engine 不一定与 watcher 同时启动（启动顺序、CI 环境无 engine 等），
 * 强行剔除会导致前端拿不到模板而阻塞「新建策略」入口；真正的强校验在用户保存策略时由
 * {@code StrategyServiceImpl#createStrategy/updateStrategyConfig} 触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyTemplateLoader {

    private static final String TEMPLATE_LOCATION = "classpath:strategies/templates/*.json";

    /**
     * config 子树允许的字段（其余顶层键如 category/tags 不进入 configJson）。
     * 序列化时按此顺序输出，保持与统一策略 Schema 字段顺序一致。
     */
    private static final List<String> CONFIG_KEYS = Arrays.asList(
            "strategy_id", "name", "description",
            "tunable_params",
            "screen_config", "trading_config", "backtest_config"
    );

    private final StrategyEngineClient engineClient;

    private final Map<String, StrategyTemplateDTO> cache = new ConcurrentHashMap<>();

    /**
     * 是否在 dev profile 下做启动校验。通过 spring.profiles.active 判断（含 dev 即视为开发模式）。
     */
    @org.springframework.beans.factory.annotation.Value("${spring.profiles.active:}")
    private String activeProfile;

    @PostConstruct
    public void load() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(TEMPLATE_LOCATION);
        } catch (Exception e) {
            log.warn("扫描策略模板目录失败，模板列表将为空: {}", e.getMessage());
            return;
        }

        if (resources.length == 0) {
            log.warn("未找到任何策略模板（{}）", TEMPLATE_LOCATION);
            return;
        }

        boolean validateEnabled = isDevProfile();
        int loaded = 0;
        for (Resource resource : resources) {
            String templateId = deriveTemplateId(resource);
            try {
                StrategyTemplateDTO dto = parseTemplate(resource, templateId);
                if (dto == null) {
                    continue;
                }
                if (validateEnabled) {
                    validateOnLoad(dto);
                }
                cache.put(templateId, dto);
                loaded++;
            } catch (Exception e) {
                log.error("加载策略模板 [{}] 失败: {}", templateId, e.getMessage(), e);
            }
        }
        log.info("策略模板加载完成：共 {} 个，模板ID: {}", loaded, cache.keySet());
    }

    /**
     * 返回全部模板（按缓存写入顺序的拷贝，调用方修改不影响缓存）。
     */
    public List<StrategyTemplateDTO> listTemplates() {
        return new ArrayList<>(cache.values());
    }

    /**
     * 按 id 获取模板；不存在抛 {@link BusinessException}(404)。
     *
     * @param id 模板文件名（不含 .json）
     */
    public StrategyTemplateDTO getTemplate(String id) {
        StrategyTemplateDTO t = cache.get(id);
        if (t == null) {
            throw new BusinessException(404, "策略模板不存在: " + id);
        }
        return t;
    }

    // ==================== 内部 ====================

    private boolean isDevProfile() {
        if (activeProfile == null || activeProfile.isBlank()) {
            return false;
        }
        return Arrays.stream(activeProfile.split(","))
                .map(String::trim)
                .anyMatch("dev"::equalsIgnoreCase);
    }

    private String deriveTemplateId(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null) {
            return "unknown";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * 解析单个模板文件，拆分 watcher 元数据与 config 子树。
     */
    private StrategyTemplateDTO parseTemplate(Resource resource, String templateId) throws Exception {
        String content;
        try (InputStream in = resource.getInputStream()) {
            content = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
        JSONObject root = JSON.parseObject(content);

        StrategyTemplateDTO dto = new StrategyTemplateDTO();
        dto.setId(templateId);

        // 元数据：优先用顶层，缺失时回落到 config 子树同名字段
        dto.setName(firstNonBlank(root.getString("name"), root.getJSONObject("config") == null
                ? null : root.getJSONObject("config").getString("name"), templateId));
        dto.setDescription(firstNonBlank(root.getString("description"),
                root.getJSONObject("config") == null ? null
                        : root.getJSONObject("config").getString("description")));
        dto.setCategory(root.getString("category"));

        // 构造 config 子树（按 CONFIG_KEYS 顺序，剔除 category/tags）
        JSONObject configSubTree = buildConfigSubTree(root);
        dto.setConfigJson(JSON.toJSONString(configSubTree));

        // scope 从 config 子树的 trading_config 结构派生（不再从模板字段读取）
        dto.setScope(deriveScopeFromConfig(configSubTree));
        return dto;
    }

    /**
     * 从 config 子树派生 scope。
     * <p>
     * signals 与 rebalance 范式互斥（spec 009-strategy-paradigm-exclusive），
     * 互斥约束由 engine validator 保证；此处防御性判断，signals 优先：
     * 有 signals → single；有 rebalance → portfolio；都没有 → single。
     */
    private String deriveScopeFromConfig(JSONObject config) {
        if (config == null) {
            return "single";
        }
        JSONObject trading = config.getJSONObject("trading_config");
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
    }

    /**
     * 从模板根 JSON 抽取 config 子树：
     * <ul>
     *   <li>若顶层有 {@code config} 字段（嵌套结构），优先取它，再用顶层同名字段补齐 strategy_id/name/description/scope；</li>
     *   <li>否则认为模板为「扁平结构」（字段直接挂在顶层，见 dual_ma.json），直接从顶层按 CONFIG_KEYS 提取。</li>
     * </ul>
     * 无论哪种来源，都剔除 {@code category/tags}，确保 configJson 严格符合统一策略 Schema。
     */
    private JSONObject buildConfigSubTree(JSONObject root) {
        JSONObject result = new JSONObject();
        JSONObject nestedConfig = root.getJSONObject("config");

        for (String key : CONFIG_KEYS) {
            Object value = null;
            if (nestedConfig != null && nestedConfig.containsKey(key)) {
                value = nestedConfig.get(key);
            } else if (root.containsKey(key)) {
                value = root.get(key);
            }
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * dev 模式下启动校验；失败不剔除，仅打 WARN。
     * engine 不可达（{@link BusinessException} 50301）时跳过校验。
     */
    private void validateOnLoad(StrategyTemplateDTO dto) {
        try {
            var errors = engineClient.validate(dto.getConfigJson());
            if (!errors.isEmpty()) {
                String first = errors.get(0).getPath() + ": " + errors.get(0).getMessage();
                log.warn("策略模板 [{}] 启动校验失败（已加载，用户保存时会被拦截）: 首个错误=[{}], 共 {} 项",
                        dto.getId(), first, errors.size());
            }
        } catch (BusinessException be) {
            // engine 不可达：跳过校验，正常缓存
            log.warn("策略模板 [{}] 启动校验跳过（engine 不可达）: {}", dto.getId(), be.getMessage());
        } catch (Exception e) {
            log.warn("策略模板 [{}] 启动校验异常（已加载）: {}", dto.getId(), e.getMessage());
        }
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String s : candidates) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /**
     * 占位：用于未来按 category 分组返回；当前 listTemplates 返回扁平列表。
     */
    @SuppressWarnings("unused")
    private Map<String, List<StrategyTemplateDTO>> groupByCategory() {
        Map<String, List<StrategyTemplateDTO>> grouped = new LinkedHashMap<>();
        for (StrategyTemplateDTO t : cache.values()) {
            grouped.computeIfAbsent(t.getCategory() == null ? "OTHER" : t.getCategory(),
                    k -> new ArrayList<>()).add(t);
        }
        return grouped;
    }
}
