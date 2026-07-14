package com.arthur.stock.controller;

import com.arthur.stock.constant.DisplayableEnum;
import com.arthur.stock.constant.RebalanceFrequencyEnum;
import com.arthur.stock.constant.RebalanceReplaceMethodEnum;
import com.arthur.stock.constant.RebalanceWeightModeEnum;
import com.arthur.stock.constant.StrategyCategoryEnum;
import com.arthur.stock.constant.StrategySchemaConstants;
import com.arthur.stock.constant.StrategyScopeEnum;
import com.arthur.stock.constant.StrategyStatusEnum;
import com.arthur.stock.constant.UniverseEnum;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.EnumOptionDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@Tag(name = "常量枚举", description = "系统常量枚举数据，用于前端下拉框等选择组件")
@Slf4j
@RestController
@RequestMapping("/constants")
public class ConstantController {

    private static final List<String> SCAN_PACKAGES = List.of(
            "com.arthur.stock.constant",
            "com.arthur.stock.model"
    );

    private Map<String, Object> cache;

    @PostConstruct
    public void init() {
        cache = new LinkedHashMap<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isIndependent()
                                && beanDefinition.getMetadata().isConcrete();
                    }
                };
        scanner.addIncludeFilter(new AssignableTypeFilter(DisplayableEnum.class));

        for (String basePackage : SCAN_PACKAGES) {
            for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
                try {
                    Class<?> clazz = Class.forName(bd.getBeanClassName());
                    if (clazz.isEnum()) {
                        registerEnum(clazz);
                    }
                } catch (ClassNotFoundException ignored) {}
            }
        }

        registerStrategyConstants();

        log.info("Loaded {} enum constants: {}", cache.size(), cache.keySet());
    }

    @Operation(summary = "获取所有枚举常量", description = "返回系统中所有实现了DisplayableEnum接口的枚举类与策略 Schema 白名单常量，用于前端下拉选择框。值类型为 List<EnumOptionDTO>（枚举）或 List<String>（策略白名单）。返回 Map<String, Object> 属于「纯键值缓存返回」例外（见 api-design §11.3-2）")
    @GetMapping
    public ApiResponse<Map<String, Object>> getAllConstants() {
        return ApiResponse.success(cache);
    }

    private void registerEnum(Class<?> clazz) {
        Object[] constants = clazz.getEnumConstants();
        if (constants == null || constants.length == 0) return;
        List<EnumOptionDTO> options = Arrays.stream(constants)
                .map(e -> (DisplayableEnum) e)
                .map(e -> new EnumOptionDTO(e.getCode(), e.getLabel()))
                .collect(Collectors.toList());
        cache.put(clazz.getSimpleName(), options);
    }

    /**
     * 注册策略管理模块（Task 14）相关常量：
     * <ul>
     *   <li>三个策略枚举的命名空间别名（strategies.categories / scopes / statuses），
     *       与自动扫描产出的 SimpleName 键并存，便于前端按业务域取值</li>
     *   <li>策略 Schema 白名单字符串列表（与 engine constants.py 对齐）</li>
     * </ul>
     */
    private void registerStrategyConstants() {
        cache.put("strategies.categories", toOptions(StrategyCategoryEnum.values()));
        cache.put("strategies.scopes", toOptions(StrategyScopeEnum.values()));
        cache.put("strategies.statuses", toOptions(StrategyStatusEnum.values()));

        cache.put("strategies.positionMethods", StrategySchemaConstants.POSITION_SIZING_METHODS);
        cache.put("strategies.sellMethods", StrategySchemaConstants.SELL_METHODS);
        cache.put("strategies.brokerProfiles", StrategySchemaConstants.BROKER_PROFILES);
        cache.put("strategies.universe", toOptions(UniverseEnum.values()));
        cache.put("strategies.rebalanceFrequency", toOptions(RebalanceFrequencyEnum.values()));
        cache.put("strategies.rebalanceReplaceMethod", toOptions(RebalanceReplaceMethodEnum.values()));
        cache.put("strategies.rebalanceWeightMode", toOptions(RebalanceWeightModeEnum.values()));
        cache.put("strategies.screenComparators", StrategySchemaConstants.SCREEN_COMPARATORS);
        cache.put("strategies.tradingComparators", StrategySchemaConstants.TRADING_COMPARATORS);
        cache.put("strategies.allowedRefs", StrategySchemaConstants.ALLOWED_REFS);
        cache.put("strategies.technicalFactors", StrategySchemaConstants.TECHNICAL_FACTOR_KEYS);
        cache.put("strategies.fundamentalFactors", StrategySchemaConstants.FUNDAMENTAL_FACTOR_KEYS);
    }

    private List<EnumOptionDTO> toOptions(DisplayableEnum[] enums) {
        return Arrays.stream(enums)
                .map(e -> new EnumOptionDTO(e.getCode(), e.getLabel()))
                .collect(Collectors.toList());
    }
}
